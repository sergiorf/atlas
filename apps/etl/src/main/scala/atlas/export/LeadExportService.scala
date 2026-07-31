package atlas.export

import atlas.config.AtlasConfig
import atlas.release.CompanyBundleService
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, lit}

final case class LeadExportRequest(
    group: String,
    state: Option[String],
    municipalityCode: Option[String],
    openedFrom: Option[String],
    openedBefore: Option[String],
    format: String,
    output: Path,
    limit: Int,
    force: Boolean
)

final case class LeadExportResult(output: Path, manifest: Path, rowCount: Long)

object LeadExportService {
  private val AllowedFormats = Set("csv", "parquet")

  def run(spark: SparkSession, config: AtlasConfig, request: LeadExportRequest): LeadExportResult = {
    require(request.group.matches("^[a-z0-9_]+$"), "Invalid --group")
    require(AllowedFormats.contains(request.format), "--format must be csv or parquet")
    require(request.limit > 0 && request.limit <= 1000000, "--limit must be between 1 and 1000000")
    if (Files.exists(request.output) && !request.force)
      throw new IllegalArgumentException(s"Export output already exists: ${request.output}")
    val source = CompanyBundleService.componentPath(config, "gold_leads_new_companies")
    var frame = spark.read.parquet(source.toString).filter(col("business_group") === request.group)
    request.state.foreach(value => frame = frame.filter(col("state_abbreviation") === value.toUpperCase))
    request.municipalityCode.foreach(value =>
      frame = frame.filter(col("receita_municipality_code") === value))
    request.openedFrom.foreach(value => frame = frame.filter(col("opening_date") >= lit(value).cast("date")))
    request.openedBefore.foreach(value => frame = frame.filter(col("opening_date") < lit(value).cast("date")))
    val selected = frame.orderBy(
      col("opening_date").desc, col("cnpj_full"), col("business_group")
    ).limit(request.limit)
    val rowCount = selected.count()
    val temporary = request.output.resolveSibling(request.output.getFileName + s".${UUID.randomUUID()}.tmp")
    try {
      request.format match {
        case "csv" => selected.coalesce(1).write.mode("errorifexists").option("header", "true").csv(temporary.toString)
        case "parquet" => selected.write.mode("errorifexists").parquet(temporary.toString)
      }
      if (Files.exists(request.output) && request.force) moveAside(request.output)
      Files.move(temporary, request.output, StandardCopyOption.ATOMIC_MOVE)
      val manifest = request.output.resolveSibling(request.output.getFileName + ".manifest.json")
      val body =
        s"""{"schema_version":1,"created_at":"${Instant.now()}","format":"${escape(request.format)}","group":"${escape(request.group)}","state":${json(request.state)},"municipality_code":${json(request.municipalityCode)},"opened_from":${json(request.openedFrom)},"opened_before":${json(request.openedBefore)},"limit":${request.limit},"row_count":$rowCount,"source":"${escape(source.toString)}","content_sha256":"${directoryHash(request.output)}"}"""
      Files.writeString(manifest, body + "\n", StandardCharsets.UTF_8)
      LeadExportResult(request.output, manifest, rowCount)
    } catch {
      case error: Throwable =>
        deleteTree(temporary)
        throw error
    }
  }

  private def moveAside(path: Path): Unit = {
    val backup = path.resolveSibling(path.getFileName + s".replaced-${Instant.now().toEpochMilli}")
    Files.move(path, backup, StandardCopyOption.ATOMIC_MOVE)
  }

  private def directoryHash(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = Files.walk(path)
    try stream.sorted().forEach { file =>
      if (Files.isRegularFile(file)) {
        digest.update(path.relativize(file).toString.getBytes(StandardCharsets.UTF_8))
        digest.update(Files.readAllBytes(file))
      }
    } finally stream.close()
    digest.digest().map("%02x".format(_)).mkString
  }

  private def deleteTree(path: Path): Unit = if (Files.exists(path)) {
    val stream = Files.walk(path)
    try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
    finally stream.close()
  }
  private def json(value: Option[String]): String = value.fold("null")(v => "\"" + escape(v) + "\"")
  private def escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
