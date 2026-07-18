package atlas

import atlas.common.SparkSessionFactory
import atlas.config.AtlasConfig
import atlas.history.EstablishmentHistoryJob
import atlas.receita.ReceitaIngestJob
import atlas.release.{EstablishmentRebuildService, PublicationLock, ReleaseDropService, ReleaseId, ReleaseInventoryService, ReleaseLayer, StaleDerivedCleanupService}
import atlas.receita.SilverEstablishmentJob
import atlas.status.{RunStatusRegistry, StatusTable}
import java.nio.file.Paths

object Main {
  private[atlas] final case class Cli(
      command: String,
      configPath: String = "conf/application.conf",
      release: Option[String] = None,
      json: Boolean = false,
      layer: Option[String] = None,
      force: Boolean = false,
      allowLegacyCurrent: Boolean = false,
      fromRelease: Option[String] = None,
      toRelease: Option[String] = None
  )
  def main(args: Array[String]): Unit = {
    val cli = parseArgs(args.toList)
    val loaded = AtlasConfig.load(cli.configPath)
    val config = cli.release.fold(loaded)(release => loaded.copy(receita = loaded.receita.copy(snapshot = ReleaseId.unsafe(release).value)))
    cli.command match {
      case "help" => println(helpText)
      case "version" => println("Atlas local CLI\nETL version: development")
      case "status" => printStatus(config.statusDir, cli.json)
      case "releases-list" => println(ReleaseInventoryService.renderList(ReleaseInventoryService.list(config)))
      case "releases-inspect" =>
        val release = ReleaseId.unsafe(cli.release.getOrElse(throw new IllegalArgumentException("Missing --release YYYY-MM")))
        println(ReleaseInventoryService.renderInspect(ReleaseInventoryService.inspect(config, release)))
      case "releases-drop-derived" =>
        val release = ReleaseId.unsafe(cli.release.getOrElse(throw new IllegalArgumentException("Missing --release YYYY-MM")))
        val layer = ReleaseLayer.parse(cli.layer.getOrElse(throw new IllegalArgumentException("Missing --layer LAYER"))).fold(message => throw new IllegalArgumentException(message), identity)
        val result = if (cli.force) ReleaseDropService.force(config, release, layer) else ReleaseDropService.plan(config, release, layer)
        println(ReleaseDropService.render(result))
      case "releases-drop-stale-derived" =>
        val result =
          if (cli.force) StaleDerivedCleanupService.force(config)
          else StaleDerivedCleanupService.plan(config)
        println(StaleDerivedCleanupService.render(result))
      case "releases-rebuild-establishments" =>
        val from = ReleaseId.unsafe(cli.fromRelease.getOrElse(throw new IllegalArgumentException("Missing --from-release YYYY-MM")))
        val to = ReleaseId.unsafe(cli.toRelease.getOrElse(throw new IllegalArgumentException("Missing --to-release YYYY-MM")))
        val plan = EstablishmentRebuildService.plan(config, from, to)
        if (cli.force) withSpark(config) { spark =>
          println(EstablishmentRebuildService.render(EstablishmentRebuildService.force(spark, config, plan)))
        }
        else println(EstablishmentRebuildService.render(plan))
      case "ingest-receita-estabelecimentos" =>
        withSpark(config) { spark =>
          val result = ReceitaIngestJob.run(spark, config)
          println(s"Ingested ${result.rowCount} rows to ${result.outputPath}")
        }
      case "normalize-receita-estabelecimentos" =>
        withSpark(config) { spark =>
          val result = SilverEstablishmentJob.run(spark, config)
          println(s"Normalized ${result.rowCount} rows to ${result.outputPath}")
        }
      case "refresh-receita-estabelecimentos" =>
        PublicationLock.withEstablishmentsLock(config) {
          withSpark(config) { spark =>
            val current = try EstablishmentHistoryJob.validateAdvance(spark, config, cli.allowLegacyCurrent)
            catch {
              case error: Throwable =>
                try EstablishmentHistoryJob.recordRejected(config, error)
                catch { case statusError: Throwable => error.addSuppressed(statusError) }
                throw error
            }
            if (current == EstablishmentHistoryJob.LegacyCurrent)
              println("WARNING: current release metadata is unknown; ordering cannot be verified and from_release will be null")
            val ingested = ReceitaIngestJob.run(spark, config)
            println(s"Ingested ${ingested.rowCount} rows to ${ingested.outputPath}")
            val result = EstablishmentHistoryJob.refresh(spark, config, cli.allowLegacyCurrent)
            println(
              s"Refreshed release ${result.release}: current=${result.currentRowCount}, " +
                s"inserted=${result.insertedCount}, updated=${result.updatedCount}, removed=${result.removedCount}"
            )
          }
        }
      case unknown => throw new IllegalArgumentException(s"Unknown command: $unknown")
    }
  }

  private def withSpark(
      config: AtlasConfig
  )(run: org.apache.spark.sql.SparkSession => Unit): Unit = {
    val spark = SparkSessionFactory.create(config.spark)
    try run(spark)
    finally spark.stop()
  }

  private[atlas] def statusOutput(statusDir: String, json: Boolean = false): String = {
    val scan = RunStatusRegistry.scan(Paths.get(statusDir))
    if (json) return RunStatusRegistry.jsonArray(scan.statuses)
    val statusText =
      if (scan.statuses.isEmpty)
        "No ETL status has been recorded yet. A successful ingest command will create status files."
      else StatusTable.render(scan.statuses)
    val errors = scan.errors.map(error => s"Malformed status file ${error.path}: ${error.message}")
    (statusText +: errors).mkString("\n")
  }

  private def printStatus(statusDir: String, json: Boolean): Unit = println(statusOutput(statusDir, json))
  private[atlas] def parseArgs(args: List[String]): Cli = args match {
    case "help" :: Nil => Cli("help")
    case "version" :: Nil => Cli("version")
    case "status" :: "--json" :: Nil => Cli("status", json = true)
    case command :: "--config" :: path :: Nil => Cli(command, path)
    case command :: "--release" :: release :: Nil => Cli(command, release = Some(release))
    case command :: "--release" :: release :: "--allow-legacy-current" :: Nil =>
      Cli(command, release = Some(release), allowLegacyCurrent = true)
    case command :: Nil                       => Cli(command)
    case "releases" :: "list" :: Nil => Cli("releases-list")
    case "releases" :: "inspect" :: "--release" :: release :: Nil => Cli("releases-inspect", release = Some(release))
    case "releases" :: "drop-derived" :: "--release" :: release :: "--layer" :: layer :: Nil =>
      Cli("releases-drop-derived", release = Some(release), layer = Some(layer))
    case "releases" :: "drop-derived" :: "--release" :: release :: "--layer" :: layer :: "--dry-run" :: Nil =>
      Cli("releases-drop-derived", release = Some(release), layer = Some(layer))
    case "releases" :: "drop-derived" :: "--release" :: release :: "--layer" :: layer :: "--force" :: Nil =>
      Cli("releases-drop-derived", release = Some(release), layer = Some(layer), force = true)
    case "releases" :: "drop-stale-derived" :: Nil =>
      Cli("releases-drop-stale-derived")
    case "releases" :: "drop-stale-derived" :: "--dry-run" :: Nil =>
      Cli("releases-drop-stale-derived")
    case "releases" :: "drop-stale-derived" :: "--force" :: Nil =>
      Cli("releases-drop-stale-derived", force = true)
    case "releases" :: "rebuild-establishments" :: "--from-release" :: from :: "--to-release" :: to :: Nil =>
      Cli("releases-rebuild-establishments", fromRelease = Some(from), toRelease = Some(to))
    case "releases" :: "rebuild-establishments" :: "--from-release" :: from :: "--to-release" :: to :: "--dry-run" :: Nil =>
      Cli("releases-rebuild-establishments", fromRelease = Some(from), toRelease = Some(to))
    case "releases" :: "rebuild-establishments" :: "--from-release" :: from :: "--to-release" :: to :: "--force" :: Nil =>
      Cli("releases-rebuild-establishments", force = true, fromRelease = Some(from), toRelease = Some(to))
    case _ =>
      throw new IllegalArgumentException(
        "Usage: atlas.Main <ingest-receita-estabelecimentos|normalize-receita-estabelecimentos|refresh-receita-estabelecimentos|status|help|version>"
      )
  }

  private[atlas] val helpText: String =
    """Atlas - Brazilian public company intelligence ETL
      |
      |Usage:
      |  atlas <command> [options]
      |
      |Pipeline commands:
      |  ingest receita estabelecimentos [--release YYYY-MM]
      |      Read configured Receita Estabelecimentos CSV files for the release, write source-faithful
      |      bronze Parquet, and emit bronze quality reports. Raw input files are never modified.
      |
      |  normalize receita estabelecimentos [--release YYYY-MM]
      |      Read the release bronze table and build a normalized silver candidate for establishments.
      |      Use this when you want to validate silver output without publishing it as current.
      |
      |  refresh receita estabelecimentos [--release YYYY-MM]
      |      Run ingest and silver normalization, compare the release with the current silver table,
      |      write compact establishment change events, and publish the release as latest current.
      |      Releases must advance chronologically. Legacy current tables require --allow-legacy-current.
      |
      |Status and release commands:
      |  status [--json]
      |      Show recorded local pipeline runs, or print the same registry as JSON for automation.
      |
      |  releases list
      |      List local releases Atlas can see across raw, bronze, silver work, reports, and history paths.
      |
      |  releases inspect --release YYYY-MM
      |      Show the raw and derived paths for one release, including which paths exist and are protected.
      |
      |  releases drop-derived --release YYYY-MM --layer LAYER [--dry-run|--force]
      |      Plan or quarantine derived data for one release. LAYER is one of bronze, silver, reports,
      |      history, or all-derived. --dry-run is the default; --force moves eligible paths to trash.
      |
      |  releases drop-stale-derived [--dry-run|--force]
      |      Plan or quarantine legacy derived paths that are no longer part of the current contract.
      |      Raw files, current silver, and compact history events are protected.
      |
      |  releases rebuild-establishments --from-release YYYY-MM --to-release YYYY-MM [--dry-run|--force]
      |      Recreate all generated establishment data chronologically from protected raw releases.
      |      Dry-run is the default; --force stages and validates a replacement before activation.
      |
      |Project commands:
      |  compile
      |      Compile the ETL project through sbt.
      |
      |  test
      |      Run the ETL test suite through sbt.
      |
      |  clean
      |      Clean ETL build outputs through sbt. Data directories are not build outputs.
      |
      |  help
      |      Show this help message.
      |
      |  version
      |      Show the local Atlas CLI version.
      |
      |Common options:
      |  --release YYYY-MM    Select the snapshot and matching YYYY-MM segment in the configured raw path.
      |  --config PATH        Use an alternate ETL HOCON configuration file.
      |""".stripMargin
}
