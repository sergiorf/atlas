package atlas

import atlas.common.SparkSessionFactory
import atlas.config.AtlasConfig
import atlas.history.EstablishmentHistoryJob
import atlas.receita.ReceitaIngestJob
import atlas.release.{
  BundleValidationService,
  CompanyBundleService,
  EstablishmentRebuildService,
  PublicationLock,
  ReleaseDropService,
  ReleaseId,
  ReleaseInventoryService,
  ReleaseLayer,
  StaleDerivedCleanupService,
  StorageCleanupService,
  StorageUsageService,
  TrashPurgeService
}
import atlas.receita.SilverEstablishmentJob
import atlas.export.{LeadExportRequest, LeadExportService}
import atlas.status.{RunStatusRegistry, StatusTable}
import java.nio.file.Paths

object Main {
  private[atlas] final case class Cli(
      command: String,
      configPath: String = "conf/application.conf",
      release: Option[String] = None,
      json: Boolean = false,
      verbose: Boolean = false,
      layer: Option[String] = None,
      force: Boolean = false,
      allowLegacyCurrent: Boolean = false,
      fromRelease: Option[String] = None,
      toRelease: Option[String] = None,
      olderThanDays: Int = 7,
      category: Option[String] = None,
      top: Int = 20,
      group: Option[String] = None,
      state: Option[String] = None,
      municipalityCode: Option[String] = None,
      openedFrom: Option[String] = None,
      openedBefore: Option[String] = None,
      format: String = "csv",
      output: Option[String] = None,
      limit: Int = 100000,
      bundleId: Option[String] = None,
      full: Boolean = false
  )
  def main(args: Array[String]): Unit = {
    val cli = parseArgs(args.toList)
    val loaded = AtlasConfig.load(cli.configPath)
    val config = cli.release.fold(loaded)(release =>
      loaded.copy(receita = loaded.receita.copy(snapshot = ReleaseId.unsafe(release).value))
    )
    cli.command match {
      case "help"    => println(helpText)
      case "version" => println("Atlas local CLI\nETL version: development")
      case "status" =>
        printStatus(config.statusDir, StatusOptions(cli.release, cli.verbose, cli.json))
      case "releases-list" =>
        println(ReleaseInventoryService.renderList(ReleaseInventoryService.list(config)))
      case "releases-inspect" =>
        val release = ReleaseId.unsafe(
          cli.release.getOrElse(throw new IllegalArgumentException("Missing --release YYYY-MM"))
        )
        println(
          ReleaseInventoryService.renderInspect(ReleaseInventoryService.inspect(config, release))
        )
      case "releases-drop-derived" =>
        val release = ReleaseId.unsafe(
          cli.release.getOrElse(throw new IllegalArgumentException("Missing --release YYYY-MM"))
        )
        val layer = ReleaseLayer
          .parse(cli.layer.getOrElse(throw new IllegalArgumentException("Missing --layer LAYER")))
          .fold(message => throw new IllegalArgumentException(message), identity)
        val result =
          if (cli.force) ReleaseDropService.force(config, release, layer)
          else ReleaseDropService.plan(config, release, layer)
        println(ReleaseDropService.render(result))
      case "releases-drop-stale-derived" =>
        val result =
          if (cli.force) StaleDerivedCleanupService.force(config)
          else StaleDerivedCleanupService.plan(config)
        println(StaleDerivedCleanupService.render(result))
      case "releases-purge-trash" =>
        val result =
          if (cli.force) TrashPurgeService.force(config, cli.olderThanDays)
          else TrashPurgeService.inspect(config, cli.olderThanDays)
        println(TrashPurgeService.render(result))
      case "storage-usage" =>
        val result = StorageUsageService.inspect(config, cli.category, cli.release)
        println(
          if (cli.json) StorageUsageService.json(result)
          else StorageUsageService.render(result, cli.top)
        )
      case "storage-cleanup" =>
        val result =
          if (cli.force) StorageCleanupService.force(config, cli.olderThanDays)
          else StorageCleanupService.inspect(config, cli.olderThanDays)
        println(
          if (cli.json) StorageCleanupService.json(result) else StorageCleanupService.render(result)
        )
      case "releases-rebuild-establishments" =>
        val from = ReleaseId.unsafe(
          cli.fromRelease.getOrElse(
            throw new IllegalArgumentException("Missing --from-release YYYY-MM")
          )
        )
        val to = ReleaseId.unsafe(
          cli.toRelease.getOrElse(
            throw new IllegalArgumentException("Missing --to-release YYYY-MM")
          )
        )
        val plan = EstablishmentRebuildService.plan(config, from, to)
        if (cli.force) withSpark(config) { spark =>
          println(
            EstablishmentRebuildService.render(
              EstablishmentRebuildService.force(spark, config, plan)
            )
          )
        }
        else println(EstablishmentRebuildService.render(plan))
      case "releases-rebuild-company-data" =>
        val from = ReleaseId.unsafe(
          cli.fromRelease.getOrElse(
            throw new IllegalArgumentException("Missing --from-release YYYY-MM")
          )
        )
        val to = ReleaseId.unsafe(
          cli.toRelease.getOrElse(
            throw new IllegalArgumentException("Missing --to-release YYYY-MM")
          )
        )
        val plan = CompanyBundleService.plan(config, from, to)
        if (cli.force) withSpark(config) { spark =>
          println(CompanyBundleService.render(CompanyBundleService.rebuild(spark, config, plan)))
        }
        else println(CompanyBundleService.render(plan))
      case "releases-inspect-bundle" =>
        val release = cli.release.map(ReleaseId.unsafe)
        println(
          CompanyBundleService
            .inspect(config, release)
            .map(CompanyBundleService.render)
            .getOrElse("No matching company-data bundle.")
        )
      case "releases-validate-bundle" =>
        val report =
          if (cli.full) {
            var value: Option[atlas.release.BundleValidationReport] = None
            withSpark(config) { spark =>
              value = Some(BundleValidationService.validate(config, cli.bundleId, full = true, Some(spark)))
            }
            value.get
          } else BundleValidationService.validate(config, cli.bundleId)
        println(if (cli.json) BundleValidationService.json(report) else BundleValidationService.render(report))
        if (report.exitCode != 0) sys.exit(report.exitCode)
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
            val current =
              try EstablishmentHistoryJob.validateAdvance(spark, config, cli.allowLegacyCurrent)
              catch {
                case error: Throwable =>
                  try EstablishmentHistoryJob.recordRejected(config, error)
                  catch { case statusError: Throwable => error.addSuppressed(statusError) }
                  throw error
              }
            if (current == EstablishmentHistoryJob.LegacyCurrent)
              println(
                "WARNING: current release metadata is unknown; ordering cannot be verified and from_release will be null"
              )
            val ingested = ReceitaIngestJob.run(spark, config)
            println(s"Ingested ${ingested.rowCount} rows to ${ingested.outputPath}")
            val result = EstablishmentHistoryJob.refresh(spark, config, cli.allowLegacyCurrent)
            println(
              s"Refreshed release ${result.release}: current=${result.currentRowCount}, " +
                s"inserted=${result.insertedCount}, updated=${result.updatedCount}, removed=${result.removedCount}"
            )
          }
        }
      case "refresh-receita-company-data" =>
        val release = ReleaseId.unsafe(cli.release.getOrElse(config.receita.snapshot))
        withSpark(config) { spark =>
          println(CompanyBundleService.render(CompanyBundleService.refresh(spark, config, release)))
        }
      case "export-leads" =>
        withSpark(config) { spark =>
          val result = LeadExportService.run(spark, config, LeadExportRequest(
            cli.group.getOrElse(throw new IllegalArgumentException("Missing --group")),
            cli.state, cli.municipalityCode, cli.openedFrom, cli.openedBefore, cli.format,
            Paths.get(cli.output.getOrElse(throw new IllegalArgumentException("Missing --output"))),
            cli.limit, cli.force
          ))
          println(s"Exported ${result.rowCount} lead rows to ${result.output}; manifest=${result.manifest}")
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

  private[atlas] final case class StatusOptions(
      release: Option[String] = None,
      verbose: Boolean = false,
      json: Boolean = false
  )

  private[atlas] def statusOutput(
      statusDir: String,
      options: StatusOptions = StatusOptions()
  ): String = {
    val scan = RunStatusRegistry.scan(Paths.get(statusDir))
    if (options.json) return RunStatusRegistry.jsonArray(scan.statuses)
    val statuses =
      options.release.fold(scan.statuses)(release => scan.statuses.filter(_.snapshot == release))
    if (options.release.nonEmpty && statuses.isEmpty) {
      val available = scan.statuses.map(_.snapshot).distinct.sorted
      throw new IllegalArgumentException(
        s"No status records found for snapshot ${options.release.get}. Available snapshots: " +
          (if (available.isEmpty) "none" else available.mkString(", "))
      )
    }
    val statusText =
      if (scan.statuses.isEmpty)
        "No ETL status has been recorded yet. A successful ingest command will create status files."
      else if (options.verbose) StatusTable.renderVerbose(statuses)
      else StatusTable.renderCompact(scan.statuses, options.release)
    val errors = scan.errors.map(error => s"Malformed status file ${error.path}: ${error.message}")
    if (errors.isEmpty) statusText
    else Seq(statusText, "REGISTRY ERRORS\n" + errors.mkString("\n")).mkString("\n\n")
  }

  private def printStatus(statusDir: String, options: StatusOptions): Unit = println(
    statusOutput(statusDir, options)
  )
  private[atlas] def parseArgs(args: List[String]): Cli = args match {
    case "help" :: Nil                            => Cli("help")
    case "version" :: Nil                         => Cli("version")
    case "status" :: tail                         => parseStatus(tail)
    case "storage" :: "usage" :: tail             => parseStorageUsage(tail)
    case "storage" :: "cleanup" :: tail           => parseStorageCleanup(tail)
    case "export-leads" :: tail                   => parseLeadExport(tail)
    case command :: "--config" :: path :: Nil     => Cli(command, path)
    case command :: "--release" :: release :: Nil => Cli(command, release = Some(release))
    case command :: "--release" :: release :: "--allow-legacy-current" :: Nil =>
      Cli(command, release = Some(release), allowLegacyCurrent = true)
    case command :: Nil              => Cli(command)
    case "releases" :: "list" :: Nil => Cli("releases-list")
    case "releases" :: "inspect" :: "--release" :: release :: Nil =>
      Cli("releases-inspect", release = Some(release))
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
    case "releases" :: "purge-trash" :: tail => parsePurgeTrash(tail)
    case "releases" :: "rebuild-establishments" :: "--from-release" :: from :: "--to-release" :: to :: Nil =>
      Cli("releases-rebuild-establishments", fromRelease = Some(from), toRelease = Some(to))
    case "releases" :: "rebuild-establishments" :: "--from-release" :: from :: "--to-release" :: to :: "--dry-run" :: Nil =>
      Cli("releases-rebuild-establishments", fromRelease = Some(from), toRelease = Some(to))
    case "releases" :: "rebuild-establishments" :: "--from-release" :: from :: "--to-release" :: to :: "--force" :: Nil =>
      Cli(
        "releases-rebuild-establishments",
        force = true,
        fromRelease = Some(from),
        toRelease = Some(to)
      )
    case "releases" :: "rebuild-company-data" :: "--from-release" :: from :: "--to-release" :: to :: Nil =>
      Cli("releases-rebuild-company-data", fromRelease = Some(from), toRelease = Some(to))
    case "releases" :: "rebuild-company-data" :: "--from-release" :: from :: "--to-release" :: to :: "--dry-run" :: Nil =>
      Cli("releases-rebuild-company-data", fromRelease = Some(from), toRelease = Some(to))
    case "releases" :: "rebuild-company-data" :: "--from-release" :: from :: "--to-release" :: to :: "--force" :: Nil =>
      Cli(
        "releases-rebuild-company-data",
        force = true,
        fromRelease = Some(from),
        toRelease = Some(to)
      )
    case "releases" :: "inspect-bundle" :: Nil => Cli("releases-inspect-bundle")
    case "releases" :: "inspect-bundle" :: "--release" :: release :: Nil =>
      Cli("releases-inspect-bundle", release = Some(release))
    case "releases" :: "validate-bundle" :: tail => parseBundleValidation(tail)
    case _ =>
      throw new IllegalArgumentException(
        "Usage: atlas.Main <ingest-receita-estabelecimentos|normalize-receita-estabelecimentos|refresh-receita-estabelecimentos|status|help|version>"
      )
  }

  private def parseBundleValidation(args: List[String]): Cli = {
    def loop(rest: List[String], bundleId: Option[String], full: Boolean, json: Boolean): Cli = rest match {
      case Nil => Cli("releases-validate-bundle", bundleId = bundleId, full = full, json = json)
      case "--bundle-id" :: value :: tail if bundleId.isEmpty => loop(tail, Some(value), full, json)
      case "--full" :: tail if !full => loop(tail, bundleId, full = true, json)
      case "--json" :: tail if !json => loop(tail, bundleId, full, json = true)
      case _ => throw new IllegalArgumentException(
        "Usage: releases validate-bundle [--bundle-id ID] [--full] [--json]"
      )
    }
    loop(args, None, full = false, json = false)
  }

  private def parseLeadExport(args: List[String]): Cli = {
    def loop(rest: List[String], cli: Cli): Cli = rest match {
      case Nil =>
        if (cli.group.isEmpty || cli.output.isEmpty)
          throw new IllegalArgumentException("export-leads requires --group and --output")
        cli
      case "--group" :: value :: tail if cli.group.isEmpty =>
        loop(tail, cli.copy(group = Some(value)))
      case "--state" :: value :: tail if cli.state.isEmpty =>
        loop(tail, cli.copy(state = Some(value)))
      case "--municipality-code" :: value :: tail if cli.municipalityCode.isEmpty =>
        loop(tail, cli.copy(municipalityCode = Some(value)))
      case "--opened-from" :: value :: tail if cli.openedFrom.isEmpty =>
        loop(tail, cli.copy(openedFrom = Some(value)))
      case "--opened-before" :: value :: tail if cli.openedBefore.isEmpty =>
        loop(tail, cli.copy(openedBefore = Some(value)))
      case "--format" :: value :: tail => loop(tail, cli.copy(format = value))
      case "--output" :: value :: tail if cli.output.isEmpty =>
        loop(tail, cli.copy(output = Some(value)))
      case "--limit" :: value :: tail =>
        val parsed = try value.toInt catch {
          case _: NumberFormatException => throw new IllegalArgumentException("--limit requires an integer")
        }
        loop(tail, cli.copy(limit = parsed))
      case "--force" :: tail => loop(tail, cli.copy(force = true))
      case _ => throw new IllegalArgumentException(
        "Usage: export-leads --group GROUP --output PATH [--state UF] [--municipality-code CODE] " +
          "[--opened-from YYYY-MM-DD] [--opened-before YYYY-MM-DD] [--format csv|parquet] [--limit N] [--force]"
      )
    }
    loop(args, Cli("export-leads"))
  }

  private def parseStatus(args: List[String]): Cli = {
    def loop(rest: List[String], release: Option[String], verbose: Boolean, json: Boolean): Cli =
      rest match {
        case Nil =>
          if (json && (verbose || release.nonEmpty))
            throw new IllegalArgumentException(
              "Usage: atlas status [--release YYYY-MM] [--verbose|--json]"
            )
          Cli("status", release = release, json = json, verbose = verbose)
        case "--release" :: value :: tail if release.isEmpty =>
          loop(tail, Some(ReleaseId.unsafe(value).value), verbose, json)
        case "--verbose" :: tail if !verbose => loop(tail, release, verbose = true, json)
        case "--json" :: tail if !json       => loop(tail, release, verbose, json = true)
        case _ =>
          throw new IllegalArgumentException(
            "Usage: atlas status [--release YYYY-MM] [--verbose|--json]"
          )
      }
    loop(args, None, verbose = false, json = false)
  }

  private def parsePurgeTrash(args: List[String]): Cli = {
    def loop(rest: List[String], force: Boolean, olderThanDays: Int): Cli = rest match {
      case Nil => Cli("releases-purge-trash", force = force, olderThanDays = olderThanDays)
      case "--dry-run" :: tail => loop(tail, force = false, olderThanDays)
      case "--force" :: tail   => loop(tail, force = true, olderThanDays)
      case "--older-than-days" :: value :: tail =>
        val days =
          try value.toInt
          catch {
            case _: NumberFormatException =>
              throw new IllegalArgumentException(
                "--older-than-days requires a non-negative integer"
              )
          }
        if (days < 0) throw new IllegalArgumentException("--older-than-days must be non-negative")
        loop(tail, force, days)
      case _ =>
        throw new IllegalArgumentException(
          "Usage: releases purge-trash [--older-than-days N] [--dry-run|--force]"
        )
    }
    loop(args, force = false, olderThanDays = 7)
  }

  private def parseStorageUsage(args: List[String]): Cli = {
    def loop(
        rest: List[String],
        category: Option[String],
        release: Option[String],
        top: Int,
        json: Boolean
    ): Cli = rest match {
      case Nil =>
        Cli("storage-usage", release = release, json = json, category = category, top = top)
      case "--category" :: value :: tail if category.isEmpty =>
        if (!StorageUsageService.Categories(value))
          throw new IllegalArgumentException(
            s"Unknown storage category '$value'. Expected one of: ${StorageUsageService.Categories.toSeq.sorted.mkString(", ")}"
          )
        loop(tail, Some(value), release, top, json)
      case "--release" :: value :: tail if release.isEmpty =>
        loop(tail, category, Some(ReleaseId.unsafe(value).value), top, json)
      case "--top" :: value :: tail =>
        val parsed =
          try value.toInt
          catch {
            case _: NumberFormatException =>
              throw new IllegalArgumentException("--top requires a positive integer")
          }
        if (parsed <= 0) throw new IllegalArgumentException("--top requires a positive integer")
        loop(tail, category, release, parsed, json)
      case "--json" :: tail if !json => loop(tail, category, release, top, json = true)
      case _ =>
        throw new IllegalArgumentException(
          "Usage: storage usage [--category CATEGORY] [--release YYYY-MM] [--top N] [--json]"
        )
    }
    loop(args, None, None, top = 20, json = false)
  }

  private def parseStorageCleanup(args: List[String]): Cli = {
    def loop(
        rest: List[String],
        force: Boolean,
        modeSeen: Boolean,
        olderThanDays: Int,
        retentionSeen: Boolean,
        json: Boolean
    ): Cli = rest match {
      case Nil =>
        if (force && json)
          throw new IllegalArgumentException(
            "Usage: storage cleanup [--older-than-days N] [--dry-run|--force] [--json]"
          )
        Cli(
          "storage-cleanup",
          force = force,
          olderThanDays = olderThanDays,
          json = json
        )
      case "--dry-run" :: tail if !modeSeen =>
        loop(tail, force = false, modeSeen = true, olderThanDays, retentionSeen, json)
      case "--force" :: tail if !modeSeen =>
        loop(tail, force = true, modeSeen = true, olderThanDays, retentionSeen, json)
      case "--older-than-days" :: value :: tail if !retentionSeen =>
        val days =
          try value.toInt
          catch {
            case _: NumberFormatException =>
              throw new IllegalArgumentException(
                "--older-than-days requires a non-negative integer"
              )
          }
        if (days < 0)
          throw new IllegalArgumentException("--older-than-days requires a non-negative integer")
        loop(tail, force, modeSeen, days, retentionSeen = true, json)
      case "--json" :: tail if !json =>
        loop(tail, force, modeSeen, olderThanDays, retentionSeen, json = true)
      case _ =>
        throw new IllegalArgumentException(
          "Usage: storage cleanup [--older-than-days N] [--dry-run|--force] [--json]"
        )
    }
    loop(
      args,
      force = false,
      modeSeen = false,
      olderThanDays = 7,
      retentionSeen = false,
      json = false
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
      |  refresh receita company-data --release YYYY-MM
      |      Build matching company, establishment, reference, and geography silver data locally,
      |      append compact history, validate the complete candidate, and atomically publish one bundle.
      |
      |  export-leads --group GROUP --output PATH [filters]
      |      Export a bounded deterministic projection of the current gold lead product.
      |
      |Status and release commands:
      |  status [--release YYYY-MM] [--verbose|--json]
      |      Summarize recorded releases and show problems for the newest snapshot. Select one release,
      |      show full forensic tables with --verbose, or print the unchanged registry with --json.
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
      |  releases purge-trash [--older-than-days N] [--dry-run|--force]
      |      Inspect quarantined generations and permanently delete only those proven safe and old enough.
      |      Dry-run and a seven-day recovery window are the defaults. Raw data is never considered.
      |
      |  releases rebuild-establishments --from-release YYYY-MM --to-release YYYY-MM [--dry-run|--force]
      |      Recreate all generated establishment data chronologically from protected raw releases.
      |      Dry-run is the default; --force stages and validates a replacement before activation.
      |
      |  releases rebuild-company-data --from-release YYYY-MM --to-release YYYY-MM [--dry-run|--force]
      |      Rebuild company and establishment state, histories, references, and geography in order.
      |      Dry-run is the default; --force publishes only after the entire bundle passes validation.
      |
      |  releases inspect-bundle [--release YYYY-MM]
      |      Read current or release-selected atomic bundle metadata without starting Spark.
      |
      |  releases validate-bundle [--bundle-id ID] [--full] [--json]
      |      Verify one immutable bundle generation and report every passed, warned, failed, or
      |      skipped check. The default is structural; --full adds Spark data-contract checks.
      |
      |  storage usage [--category CATEGORY] [--release YYYY-MM] [--top N] [--json]
      |      Inventory Atlas data and Spark temporary storage without deleting anything. Show exact
      |      protection policies and the guarded next step for each configured location.
      |
      |  storage cleanup [--older-than-days N] [--dry-run|--force] [--json]
      |      Plan unified cleanup of eligible trash and failed company bundles. Dry-run and seven
      |      days are defaults; failed bundles are quarantined before a later purge can delete them.
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
