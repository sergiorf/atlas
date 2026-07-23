package atlas.receita

import atlas.config.AtlasConfig
import atlas.release.{ReleaseId, ReleasePaths}
import com.typesafe.config.{Config, ConfigFactory}
import java.io.{BufferedInputStream, BufferedOutputStream}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.zip.{GZIPInputStream, ZipFile}
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.DecimalType
import org.apache.spark.storage.StorageLevel
import scala.collection.JavaConverters._

final case class CompanyDataManifest(
    release: String,
    manifestPath: Path,
    manifestHash: String,
    datasets: Map[String, Seq[Path]],
    tom: Path,
    tomHash: String,
    ibge: Path,
    ibgeHash: String,
    ibgeEncoding: Option[String]
)

final case class CompanyDataBuildResult(
    release: String,
    companyCount: Long,
    referenceCounts: Map[String, Long],
    geographyCount: Long,
    bronzeCompanies: Path,
    silverCompanies: Path,
    geography: Path,
    manifest: CompanyDataManifest
)

object CompanyDataPaths {
  def rawRoot(config: AtlasConfig): Path = {
    val release = ReleaseId.unsafe(config.receita.snapshot)
    val configured = Option(config.receita.companyDataRawDir).map(_.trim).filter(_.nonEmpty).getOrElse {
      val establishment = ReleasePaths.rawDirForRelease(config.receita.rawDir, release)
      val marker = s"${java.io.File.separator}estabelecimentos${java.io.File.separator}extracted"
      if (establishment.endsWith(marker)) establishment.dropRight(marker.length) + s"${java.io.File.separator}company-data"
      else Paths.get(establishment).getParent.resolve("company-data").toString
    }
    Paths.get(ReleasePaths.rawDirForRelease(configured, release))
  }

  def atlasRoot(config: AtlasConfig): Path = ReleasePaths(config).atlasRoot
  def workRoot(config: AtlasConfig): Path = atlasRoot(config).resolve("work/receita/company-data").resolve(s"release=${config.receita.snapshot}")
  def extractedRoot(config: AtlasConfig): Path = workRoot(config).resolve("extracted")
  def bronzeCompanies(config: AtlasConfig): Path = Paths.get(config.receita.bronzeDir).resolve("empresas").resolve(s"release=${config.receita.snapshot}")
  def bronzeReference(config: AtlasConfig, dimension: String): Path = Paths.get(config.receita.bronzeDir).resolve("references").resolve(dimension).resolve(s"release=${config.receita.snapshot}")
  def silverCompanies(config: AtlasConfig): Path = Paths.get(config.receita.silverDir).resolve("companies_current")
  def silverCompanyCandidate(config: AtlasConfig): Path = workRoot(config).resolve("companies_candidate")
  def silverReference(config: AtlasConfig, dimension: String): Path = Paths.get(config.receita.silverDir).resolve("references").resolve(dimension).resolve(s"release=${config.receita.snapshot}")
  def geography(config: AtlasConfig): Path = Paths.get(config.receita.silverDir).resolve("geography/municipalities").resolve(s"version=${config.receita.snapshot}")
  def qualityRoot(config: AtlasConfig): Path = atlasRoot(config).resolve("quality/receita/company-data").resolve(config.receita.snapshot)
}

object CompanyDataManifestReader {
  private val Required = Map(
    "empresas" -> "empresas",
    "cnae" -> "cnae",
    "municipios" -> "municipality",
    "naturezas" -> "legal_nature",
    "paises" -> "country",
    "qualificacoes" -> "partner_qualification",
    "motivos" -> "registration_status_reason"
  )

  def readAndValidate(config: AtlasConfig): CompanyDataManifest = {
    val raw = CompanyDataPaths.rawRoot(config).toAbsolutePath.normalize()
    val path = raw.resolve("source-manifest.json")
    if (!Files.isRegularFile(path)) throw new IllegalArgumentException(s"Missing company-data manifest: $path")
    val root = ConfigFactory.parseFile(path.toFile).resolve()
    val release = root.getString("release")
    if (release != config.receita.snapshot)
      throw new IllegalArgumentException(s"Company-data manifest release $release does not match ${config.receita.snapshot}")
    val entries = root.getConfigList("datasets").asScala
    val duplicateNames = entries.groupBy(_.getString("logical_name")).collect { case (name, values) if values.size > 1 => name }
    if (duplicateNames.nonEmpty) throw new IllegalArgumentException(s"Duplicate manifest datasets: ${duplicateNames.mkString(", ")}")
    val byName = entries.map(value => value.getString("logical_name") -> value).toMap
    val missing = Required.keySet.diff(byName.keySet)
    if (missing.nonEmpty) throw new IllegalArgumentException(s"Missing company-data datasets: ${missing.toSeq.sorted.mkString(", ")}")
    val datasets = Required.map { case (source, target) =>
      val value = byName(source)
      if (value.getString("release") != release) throw new IllegalArgumentException(s"Mixed release for dataset $source")
      val archives = value.getConfigList("archives").asScala.map { archive =>
        verified(raw, archive.getString("path"), archive.getString("sha256"))
      }
      target -> archives
    }
    val tomConfig = root.getConfig("references.tom")
    val ibgeConfig = root.getConfig("references.ibge_localities")
    val tom = verified(raw, tomConfig.getString("path"), tomConfig.getString("sha256"))
    val ibge = verified(raw, ibgeConfig.getString("path"), ibgeConfig.getString("sha256"))
    CompanyDataManifest(
      release, path, sha256(path), datasets, tom, tomConfig.getString("sha256"), ibge,
      ibgeConfig.getString("sha256"), if (ibgeConfig.hasPath("content_encoding")) Some(ibgeConfig.getString("content_encoding")) else None
    )
  }

  private def verified(root: Path, relative: String, expected: String): Path = {
    val path = root.resolve(relative).normalize()
    if (!path.startsWith(root)) throw new IllegalArgumentException(s"Manifest path escapes raw root: $relative")
    if (!Files.isRegularFile(path)) throw new IllegalArgumentException(s"Missing manifest input: $path")
    val observed = sha256(path)
    if (observed != expected) throw new IllegalArgumentException(s"SHA-256 mismatch for $path: expected $expected, observed $observed")
    path
  }

  private[atlas] def sha256(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = new BufferedInputStream(Files.newInputStream(path))
    val buffer = new Array[Byte](1024 * 1024)
    try Iterator.continually(in.read(buffer)).takeWhile(_ >= 0).foreach(count => if (count > 0) digest.update(buffer, 0, count))
    finally in.close()
    digest.digest().map("%02x".format(_)).mkString
  }
}

object CompanyDataPipeline {
  private val SourceToDimension = Map(
    "cnae" -> "cnae",
    "municipality" -> "municipality",
    "legal_nature" -> "legal_nature",
    "country" -> "country",
    "partner_qualification" -> "partner_qualification",
    "registration_status_reason" -> "registration_status_reason"
  )

  def build(spark: SparkSession, config: AtlasConfig): CompanyDataBuildResult = {
    val manifest = CompanyDataManifestReader.readAndValidate(config)
    val extracted = extract(manifest, config)
    val bronzeCompanies = writeBronzeCompanies(spark, config, extracted("empresas"))
    val references = SourceToDimension.keys.toSeq.sorted.map { dimension =>
      dimension -> writeReference(spark, config, dimension, extracted(dimension))
    }.toMap
    val geography = writeGeography(spark, config, manifest)
    val companyCount = writeSilverCompanies(spark, config, bronzeCompanies, references)
    CompanyDataBuildResult(
      config.receita.snapshot,
      companyCount,
      references.map { case (name, frame) => name -> frame.count() },
      geography.count(),
      CompanyDataPaths.bronzeCompanies(config),
      CompanyDataPaths.silverCompanyCandidate(config),
      CompanyDataPaths.geography(config),
      manifest
    )
  }

  private[atlas] def writeBronzeCompanies(spark: SparkSession, config: AtlasConfig, inputs: Seq[Path]): DataFrame = {
    val raw = readCsv(spark, inputs, CompanyDataSchemas.empresasRaw)
    val capitalText = regexp_replace(nullable(col("share_capital_raw")), ",", ".")
    val transformed = raw.select(
      upper(regexp_replace(nullable(col("cnpj_root")), "[./-]", "")).as("cnpj_root"),
      nullable(col("legal_name")).as("legal_name"),
      nullable(col("legal_nature_code")).as("legal_nature_code"),
      nullable(col("responsible_qualification_code")).as("responsible_qualification_code"),
      nullable(col("share_capital_raw")).as("share_capital_raw"),
      when(capitalText.rlike("^[0-9]+([.][0-9]{1,2})?$"), capitalText.cast(DecimalType(20, 2))).as("share_capital"),
      nullable(col("company_size_code")).as("company_size_code"),
      nullable(col("responsible_federative_entity")).as("responsible_federative_entity"),
      lit("receita_cnpj_empresas").as("source_name"),
      input_file_name().as("source_file"),
      current_timestamp().as("ingestion_timestamp"),
      lit(config.receita.snapshot).as("release")
    )
    transformed.write.mode("overwrite").parquet(CompanyDataPaths.bronzeCompanies(config).toString)
    spark.read.parquet(CompanyDataPaths.bronzeCompanies(config).toString)
  }

  private[atlas] def writeReference(
      spark: SparkSession,
      config: AtlasConfig,
      dimension: String,
      inputs: Seq[Path]
  ): DataFrame = {
    val bronze = readCsv(spark, inputs, CompanyDataSchemas.referenceRaw)
      .select(
        nullable(col("code")).as("code"), nullable(col("description")).as("description"),
        lit(config.receita.snapshot).as("release"), lit(s"receita_cnpj_$dimension").as("source_name"),
        input_file_name().as("source_file"), current_timestamp().as("ingestion_timestamp")
      )
      .withColumn("record_hash", sha2(concat_ws("|", col("code"), col("description")), 256))
    bronze.write.mode("overwrite").parquet(CompanyDataPaths.bronzeReference(config, dimension).toString)
    val candidate = spark.read.parquet(CompanyDataPaths.bronzeReference(config, dimension).toString)
      .persist(StorageLevel.DISK_ONLY)
    try {
      if (candidate.filter(col("code").isNull || col("description").isNull).limit(1).count() > 0)
        throw new IllegalStateException(s"Reference $dimension contains blank code or description")
      if (candidate.groupBy("code").agg(countDistinct("description").as("values")).filter(col("values") > 1).limit(1).count() > 0)
        throw new IllegalStateException(s"Reference $dimension contains conflicting codes")
      val output = CompanyDataPaths.silverReference(config, dimension)
      candidate.dropDuplicates("code", "description").write.mode("overwrite").parquet(output.toString)
      spark.read.parquet(output.toString)
    } finally candidate.unpersist()
  }

  private[atlas] def writeGeography(spark: SparkSession, config: AtlasConfig, manifest: CompanyDataManifest): DataFrame = {
    val tomRows = spark.read.schema(CompanyDataSchemas.tomMunicipalitiesRaw)
      .option("header", "false").option("sep", ";").option("encoding", "ISO-8859-1")
      .option("quote", "\"").option("escape", "\"").csv(manifest.tom.toString)
      .select(CompanyDataSchemas.tomMunicipalityColumns.map(name => nullable(col(name)).as(name)): _*)
    val officialHeader =
      col("receita_municipality_code") === "CÓDIGO DO MUNICÍPIO - TOM" &&
        col("ibge_municipality_code") === "CÓDIGO DO MUNICÍPIO - IBGE" &&
        col("receita_municipality_name") === "MUNICÍPIO - TOM" &&
        col("ibge_municipality_name") === "MUNICÍPIO - IBGE" &&
        col("state_abbreviation") === "UF"
    val tom = tomRows.filter(!coalesce(officialHeader, lit(false)))
      .withColumn("is_exterior", coalesce(
        col("receita_municipality_code") === "9707" &&
          col("ibge_municipality_code") === "0" &&
          col("receita_municipality_name") === "EXTERIOR" &&
          col("state_abbreviation") === "EX",
        lit(false)
      ))
    val ibgePath = decodedIbge(manifest, config)
    val ibge = spark.read.schema(CompanyDataSchemas.ibgeMunicipalityRaw).option("multiLine", "true").json(ibgePath.toString)
      .select(
        col("id").as("ibge_code"), col("nome").as("official_name"),
        col("`regiao-imediata`.id").as("immediate_region_code"), col("`regiao-imediata`.nome").as("immediate_region_name"),
        col("`regiao-imediata`.`regiao-intermediaria`.id").as("intermediate_region_code"),
        col("`regiao-imediata`.`regiao-intermediaria`.nome").as("intermediate_region_name"),
        col("`regiao-imediata`.`regiao-intermediaria`.UF.id").as("state_code"),
        col("`regiao-imediata`.`regiao-intermediaria`.UF.sigla").as("official_state_abbreviation"),
        col("`regiao-imediata`.`regiao-intermediaria`.UF.nome").as("state_name"),
        col("`regiao-imediata`.`regiao-intermediaria`.UF.regiao.id").as("region_code"),
        col("`regiao-imediata`.`regiao-intermediaria`.UF.regiao.sigla").as("region_abbreviation"),
        col("`regiao-imediata`.`regiao-intermediaria`.UF.regiao.nome").as("region_name")
      )
    if (tom.groupBy("receita_municipality_code").count().filter(col("count") > 1).limit(1).count() > 0)
      throw new IllegalStateException("TOM contains ambiguous Receita municipality codes")
    if (ibge.groupBy("ibge_code").count().filter(col("count") > 1).limit(1).count() > 0)
      throw new IllegalStateException("IBGE contains duplicate municipality codes")
    val joined = tom.join(ibge, tom("ibge_municipality_code") === ibge("ibge_code"), "left")
      .select(
        col("receita_municipality_code"), col("receita_municipality_name"), col("ibge_municipality_code"),
        col("official_name").as("ibge_municipality_name"), col("immediate_region_code"), col("immediate_region_name"),
        col("intermediate_region_code"), col("intermediate_region_name"), col("state_code"),
        when(col("is_exterior"), col("state_abbreviation")).otherwise(col("official_state_abbreviation")).as("state_abbreviation"),
        col("state_name"), col("region_code"),
        col("region_abbreviation"), col("region_name"), lit(manifest.tomHash).as("tom_source_hash"),
        lit(manifest.ibgeHash).as("ibge_source_hash"), col("is_exterior"), current_timestamp().as("reference_as_of")
      )
    if (joined.filter(!col("is_exterior") &&
        (col("ibge_municipality_name").isNull || col("state_code").isNull || col("region_code").isNull)).limit(1).count() > 0)
      throw new IllegalStateException("TOM-to-IBGE geography contains unmatched or parentless municipalities")
    joined.write.mode("overwrite").parquet(CompanyDataPaths.geography(config).toString)
    spark.read.parquet(CompanyDataPaths.geography(config).toString)
  }

  private[atlas] def writeSilverCompanies(
      spark: SparkSession,
      config: AtlasConfig,
      bronze: DataFrame,
      references: Map[String, DataFrame]
  ): Long = {
    val candidate = bronze
      .withColumn("_invalid", coalesce(
        col("cnpj_root").isNull || !col("cnpj_root").rlike("^[0-9A-Z]{8}$") ||
          (col("share_capital_raw").isNotNull && col("share_capital").isNull) || col("share_capital") < 0,
        lit(false)
      ))
      .persist(StorageLevel.DISK_ONLY)
    try {
      val malformed = candidate.filter(col("_invalid"))
      val malformedCount = malformed.count()
      if (malformedCount > 0)
        malformed.drop("_invalid").write.mode("overwrite").parquet(CompanyDataPaths.qualityRoot(config).resolve("malformed_companies").toString)
      if (malformedCount > 0)
        throw new IllegalStateException(s"Company quality gate rejected $malformedCount malformed rows")
      val valid = candidate.filter(!col("_invalid")).drop("_invalid")
      if (valid.groupBy("cnpj_root").count().filter(col("count") > 1).limit(1).count() > 0)
        throw new IllegalStateException("Company quality gate rejected duplicate cnpj_root values")
      val legal = references("legal_nature").select(col("code").as("legal_ref_code"), col("description").as("legal_nature_description"))
      val qualification = references("partner_qualification").select(col("code").as("qualification_ref_code"), col("description").as("responsible_qualification_description"))
      val enriched = valid.join(legal, col("legal_nature_code") === col("legal_ref_code"), "left")
        .join(qualification, col("responsible_qualification_code") === col("qualification_ref_code"), "left")
      val missingReferences = enriched
        .select(
          col("cnpj_root"),
          explode(array(
            struct(
              lit("legal_nature").as("dimension"), col("legal_nature_code").as("code"),
              col("legal_nature_description").as("description")
            ),
            struct(
              lit("partner_qualification").as("dimension"), col("responsible_qualification_code").as("code"),
              col("responsible_qualification_description").as("description")
            )
          )).as("reference")
        )
        .select(col("cnpj_root"), col("reference.dimension"), col("reference.code"), col("reference.description"))
        .filter(col("code").isNotNull && col("description").isNull)
        .drop("description")
        .withColumn("release", lit(config.receita.snapshot))
      if (missingReferences.limit(1).count() > 0)
        missingReferences.write.mode("overwrite")
          .parquet(CompanyDataPaths.qualityRoot(config).resolve("missing_reference_descriptions").toString)
      val published = enriched.select(
        col("cnpj_root"), col("legal_name"), col("legal_nature_code"), col("legal_nature_description"),
        col("responsible_qualification_code"), col("responsible_qualification_description"), col("share_capital"),
        col("company_size_code"), col("responsible_federative_entity"), col("source_file"), col("ingestion_timestamp"),
        current_timestamp().as("silver_transformation_timestamp"), lit(config.receita.snapshot).as("release")
      ).withColumn("record_hash", sha2(concat_ws("|", Seq(
        "legal_name", "legal_nature_code", "responsible_qualification_code", "share_capital",
        "company_size_code", "responsible_federative_entity"
      ).map(name => coalesce(col(name).cast("string"), lit("∅"))): _*), 256))
      published.write.mode("overwrite").parquet(CompanyDataPaths.silverCompanyCandidate(config).toString)
      published.count()
    } finally candidate.unpersist()
  }

  private def extract(manifest: CompanyDataManifest, config: AtlasConfig): Map[String, Seq[Path]] = {
    val root = CompanyDataPaths.extractedRoot(config)
    deleteTree(root)
    Files.createDirectories(root)
    manifest.datasets.map { case (name, archives) =>
      val targetRoot = root.resolve(name)
      Files.createDirectories(targetRoot)
      val members = archives.flatMap { archive =>
        val zip = new ZipFile(archive.toFile)
        try zip.entries().asScala.filterNot(_.isDirectory).map { entry =>
          val target = targetRoot.resolve(s"${archive.getFileName}-${entry.getName.replace('/', '_')}").normalize()
          val in = new BufferedInputStream(zip.getInputStream(entry))
          val out = new BufferedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))
          try {
            val buffer = new Array[Byte](1024 * 1024)
            Iterator.continually(in.read(buffer)).takeWhile(_ >= 0).foreach(count => if (count > 0) out.write(buffer, 0, count))
          } finally { out.close(); in.close() }
          target
        }.toSeq finally zip.close()
      }
      name -> members
    }
  }

  private def decodedIbge(manifest: CompanyDataManifest, config: AtlasConfig): Path = {
    if (!manifest.ibgeEncoding.contains("gzip")) manifest.ibge
    else {
      val target = CompanyDataPaths.extractedRoot(config).resolve("ibge-municipalities.json")
      val in = new GZIPInputStream(Files.newInputStream(manifest.ibge))
      val out = new BufferedOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))
      try {
        val buffer = new Array[Byte](1024 * 1024)
        Iterator.continually(in.read(buffer)).takeWhile(_ >= 0).foreach(count => if (count > 0) out.write(buffer, 0, count))
      } finally { out.close(); in.close() }
      target
    }
  }

  private def readCsv(spark: SparkSession, inputs: Seq[Path], schema: org.apache.spark.sql.types.StructType): DataFrame =
    spark.read.schema(schema).option("header", "false").option("sep", ";").option("encoding", "ISO-8859-1")
      .option("quote", "\"").option("escape", "\"").csv(inputs.map(_.toString): _*)

  private def nullable(value: Column): Column = when(length(trim(value)) === 0, lit(null).cast("string")).otherwise(trim(value))

  private def deleteTree(path: Path): Unit = if (Files.exists(path)) {
    val stream = Files.walk(path)
    try stream.iterator().asScala.toSeq.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
    finally stream.close()
  }
}
