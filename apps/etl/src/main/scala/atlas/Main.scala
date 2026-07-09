package atlas

import atlas.common.SparkSessionFactory
import atlas.config.AtlasConfig
import atlas.history.EstablishmentHistoryJob
import atlas.receita.ReceitaIngestJob
import atlas.release.{ReleaseDropService, ReleaseId, ReleaseInventoryService, ReleaseLayer}
import atlas.receita.SilverEstablishmentJob
import atlas.status.{RunStatusRegistry, StatusTable}
import java.nio.file.Paths

object Main {
  private[atlas] final case class Cli(command: String, configPath: String = "conf/application.conf", release: Option[String] = None, json: Boolean = false, layer: Option[String] = None, force: Boolean = false)
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
        withSpark(config) { spark =>
          val ingested = ReceitaIngestJob.run(spark, config)
          println(s"Ingested ${ingested.rowCount} rows to ${ingested.outputPath}")
          val result = EstablishmentHistoryJob.refresh(spark, config)
          println(
            s"Refreshed release ${result.release}: current=${result.currentRowCount}, " +
              s"inserted=${result.insertedCount}, updated=${result.updatedCount}, removed=${result.removedCount}"
          )
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
    case command :: Nil                       => Cli(command)
    case "releases" :: "list" :: Nil => Cli("releases-list")
    case "releases" :: "inspect" :: "--release" :: release :: Nil => Cli("releases-inspect", release = Some(release))
    case "releases" :: "drop-derived" :: "--release" :: release :: "--layer" :: layer :: Nil =>
      Cli("releases-drop-derived", release = Some(release), layer = Some(layer))
    case "releases" :: "drop-derived" :: "--release" :: release :: "--layer" :: layer :: "--dry-run" :: Nil =>
      Cli("releases-drop-derived", release = Some(release), layer = Some(layer))
    case "releases" :: "drop-derived" :: "--release" :: release :: "--layer" :: layer :: "--force" :: Nil =>
      Cli("releases-drop-derived", release = Some(release), layer = Some(layer), force = true)
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
      |Commands:
      |  ingest receita estabelecimentos          Ingest Receita establishments to bronze
      |  normalize receita estabelecimentos       Build latest normalized silver establishments
      |  refresh receita estabelecimentos         Ingest, normalize, compare, and publish latest current
      |  status [--json]                          Show local pipeline execution status
      |  releases list                            List known local releases
      |  releases inspect --release YYYY-MM       Inspect raw and derived paths for a release
      |  releases drop-derived --release YYYY-MM --layer LAYER [--dry-run|--force]
      |  compile                                  Compile the ETL project
      |  test                                     Run ETL tests
      |  clean                                    Clean ETL build outputs
      |  help                                     Show this help message
      |  version                                  Show Atlas CLI version
      |""".stripMargin
}
