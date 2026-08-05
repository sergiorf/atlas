package atlas.receita

import atlas.config.AtlasConfig
import com.typesafe.config.ConfigFactory
import java.io.File
import java.nio.file.{Files, Path}
import java.time.{LocalDate, YearMonth}
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.storage.StorageLevel
import scala.collection.JavaConverters._

final case class CompanyProductsResult(
    taxRegimeCount: Long,
    partnerCount: Long,
    partnerFieldQualityIssueCount: Long,
    relationshipCount: Long,
    profileCount: Long,
    networkCount: Long,
    leadCount: Long
)

/** v0.3b company products derived only from the same-release atomic silver candidate. */
object CompanyProductsPipeline {
  private val EarliestSupportedPartnerDate = LocalDate.of(1582, 10, 15)
  val RelationshipRuleVersion = "1"
  val GraphCalculationVersion = "1"
  val MaximumMaterializedPathDepth = 3
  val MaximumCycleSearchDepth = 6

  def build(spark: SparkSession, config: AtlasConfig, manifest: CompanyDataManifest): CompanyProductsResult = {
    if (manifest.manifestVersion < 2)
      throw new IllegalArgumentException(
        s"v0.3b publication requires company-data manifest version 2; found ${manifest.manifestVersion}"
      )
    val simplesInputs = extractedInputs(config, "simples")
    val sociosInputs = extractedInputs(config, "socios")
    val companies = spark.read.parquet(CompanyDataPaths.silverCompanies(config).toString)
    val establishments = spark.read.parquet(atlas.release.ReleasePaths(config).silverCurrent.toString)
    val references = spark.read.parquet(
      CompanyDataPaths.silverReference(config, "partner_qualification").toString
    )
    val geography = spark.read.parquet(CompanyDataPaths.geography(config).toString)

    val tax = buildTaxRegime(spark, config, simplesInputs, companies)
    val partners = buildPartners(spark, config, sociosInputs, companies, references)
    val partnerFieldQualityIssueCount = {
      val path = CompanyDataPaths.qualityRoot(config).resolve("partner_field_quality_issues")
      if (Files.exists(path)) spark.read.parquet(path.toString).count() else 0L
    }
    val relationships = buildRelationships(spark, config, partners, companies)
    tax.write.mode("overwrite").parquet(CompanyDataPaths.silverTaxRegime(config).toString)
    partners.write.mode("overwrite").parquet(CompanyDataPaths.silverPartners(config).toString)
    writeRelationshipHistory(spark, config, relationships)
    val profiles = buildProfiles(config, companies, establishments, tax, relationships)
    profiles.write.mode("overwrite").parquet(CompanyDataPaths.goldCompanyProfiles(config).toString)
    val network = buildNetwork(spark, config, relationships)
    network.write.mode("overwrite").parquet(CompanyDataPaths.goldPartnerNetwork(config).toString)
    val leads = buildLeads(spark, config, companies, establishments, geography)
    leads.write.mode("overwrite").parquet(CompanyDataPaths.goldLeads(config).toString)

    CompanyProductsResult(
      tax.count(), partners.count(), partnerFieldQualityIssueCount, relationships.count(), profiles.count(),
      network.count(), leads.count()
    )
  }

  private[atlas] def buildTaxRegime(
      spark: SparkSession,
      config: AtlasConfig,
      inputs: Seq[Path],
      companies: DataFrame
  ): DataFrame = {
    val bronze = readCsv(spark, inputs, CompanyDataSchemas.simplesRaw).select(
      root(col("cnpj_root")).as("cnpj_root"),
      nullable(col("simples_option_raw")).as("simples_option_raw"),
      nullable(col("simples_option_date_raw")).as("simples_option_date_raw"),
      nullable(col("simples_exclusion_date_raw")).as("simples_exclusion_date_raw"),
      nullable(col("mei_option_raw")).as("mei_option_raw"),
      nullable(col("mei_option_date_raw")).as("mei_option_date_raw"),
      nullable(col("mei_exclusion_date_raw")).as("mei_exclusion_date_raw"),
      lit("receita_cnpj_simples").as("source_name"),
      input_file_name().as("source_file"),
      current_timestamp().as("ingestion_timestamp"),
      lit(config.receita.snapshot).as("release")
    ).withColumn(
      "record_hash",
      sha2(concat_ws("|", CompanyDataSchemas.simplesColumns.map(name =>
        coalesce(col(name).cast("string"), lit("∅"))): _*), 256)
    )
    bronze.write.mode("overwrite").parquet(CompanyDataPaths.bronzeSimples(config).toString)
    val candidate = spark.read.parquet(CompanyDataPaths.bronzeSimples(config).toString)
      .withColumn("_invalid", col("cnpj_root").isNull || !col("cnpj_root").rlike("^[0-9A-Z]{8}$") ||
        !coalesce(col("simples_option_raw").isin("S", "N") || col("simples_option_raw").isNull, lit(false)) ||
        !coalesce(col("mei_option_raw").isin("S", "N") || col("mei_option_raw").isNull, lit(false)) ||
        invalidSourceDate(col("simples_option_date_raw")) ||
        invalidSourceDate(col("simples_exclusion_date_raw")) ||
        invalidSourceDate(col("mei_option_date_raw")) ||
        invalidSourceDate(col("mei_exclusion_date_raw")))
      .persist(StorageLevel.DISK_ONLY)
    try {
      writeDiagnostic(candidate.filter(col("_invalid")), CompanyDataPaths.qualityRoot(config).resolve("malformed_simples"))
      val valid = candidate.filter(!col("_invalid")).drop("_invalid")
      val duplicates = valid.groupBy("cnpj_root").count().filter(col("count") > 1).select("cnpj_root")
      writeDiagnostic(valid.join(duplicates, Seq("cnpj_root")), CompanyDataPaths.qualityRoot(config).resolve("duplicate_simples"))
      val accepted = valid.join(duplicates, Seq("cnpj_root"), "left_anti")
        .join(companies.select("cnpj_root"), Seq("cnpj_root"), "left_semi")
        .select(
          col("cnpj_root"),
          indicator(col("simples_option_raw")).as("is_simples"),
          sourceDate(col("simples_option_date_raw")).as("simples_option_date"),
          sourceDate(col("simples_exclusion_date_raw")).as("simples_exclusion_date"),
          indicator(col("mei_option_raw")).as("is_mei"),
          sourceDate(col("mei_option_date_raw")).as("mei_option_date"),
          sourceDate(col("mei_exclusion_date_raw")).as("mei_exclusion_date"),
          col("source_file"), col("ingestion_timestamp"),
          current_timestamp().as("silver_transformation_timestamp"),
          col("release"), col("record_hash")
        )
      accepted.write.mode("overwrite").parquet(CompanyDataPaths.silverTaxRegimeCandidate(config).toString)
      spark.read.parquet(CompanyDataPaths.silverTaxRegimeCandidate(config).toString)
    } finally candidate.unpersist()
  }

  private[atlas] def buildPartners(
      spark: SparkSession,
      config: AtlasConfig,
      inputs: Seq[Path],
      companies: DataFrame,
      qualifications: DataFrame
  ): DataFrame = {
    val raw = readCsv(spark, inputs, CompanyDataSchemas.sociosRaw)
    val bronze = raw.select(
      root(col("source_company_cnpj_root")).as("source_company_cnpj_root"),
      nullable(col("participant_type_code")).as("participant_type_code"),
      nullable(col("participant_name")).as("participant_name"),
      upper(regexp_replace(nullable(col("participant_identifier_raw")), "[./-]", "")).as("participant_identifier_raw"),
      nullable(col("participant_qualification_code")).as("participant_qualification_code"),
      nullable(col("entry_date_raw")).as("entry_date_raw"),
      nullable(col("country_code")).as("country_code"),
      nullable(col("representative_identifier_raw")).as("representative_identifier_raw"),
      nullable(col("representative_name")).as("representative_name"),
      nullable(col("representative_qualification_code")).as("representative_qualification_code"),
      nullable(col("age_range_code")).as("age_range_code"),
      lit("receita_cnpj_socios").as("source_name"), input_file_name().as("source_file"),
      current_timestamp().as("ingestion_timestamp"), lit(config.receita.snapshot).as("release")
    ).withColumn("partner_record_id", sha2(concat_ws("|",
      CompanyDataSchemas.sociosColumns.map(name => coalesce(col(name).cast("string"), lit("∅"))): _*), 256))
    bronze.write.mode("overwrite").parquet(CompanyDataPaths.bronzePartners(config).toString)
    val qualification = qualifications.select(
      col("code").as("_qualification_code"), col("description").as("participant_qualification_description")
    )
    val candidate = spark.read.parquet(CompanyDataPaths.bronzePartners(config).toString)
      .withColumn("_invalid", col("source_company_cnpj_root").isNull ||
        !col("source_company_cnpj_root").rlike("^[0-9A-Z]{8}$") ||
        !col("participant_type_code").isin("1", "2", "3"))
      .withColumn("_entry_date_parsed", sourceDate(col("entry_date_raw")))
      .withColumn("_entry_date_quality_reason", partnerDateQualityReason(
        col("entry_date_raw"), col("_entry_date_parsed"), config.receita.snapshot
      ))
      .persist(StorageLevel.DISK_ONLY)
    try {
      writeDiagnostic(candidate.filter(col("_invalid"))
        .drop("_entry_date_parsed", "_entry_date_quality_reason"),
        CompanyDataPaths.qualityRoot(config).resolve("malformed_partners"))
      val fieldQualityIssues = candidate.filter(!col("_invalid") && col("_entry_date_quality_reason").isNotNull)
        .select(
          col("partner_record_id"), col("source_company_cnpj_root"),
          lit("entry_date_raw").as("field_name"), col("entry_date_raw").as("raw_value"),
          col("_entry_date_quality_reason").as("quality_reason"), col("source_file"), col("release")
        )
      writeDiagnostic(fieldQualityIssues,
        CompanyDataPaths.qualityRoot(config).resolve("partner_field_quality_issues"))
      val accepted = candidate.filter(!col("_invalid")).drop("_invalid")
        .join(companies.select("cnpj_root").withColumnRenamed("cnpj_root", "_source_root"),
          col("source_company_cnpj_root") === col("_source_root"), "left_semi")
        .join(qualification,
          col("participant_qualification_code") === col("_qualification_code"), "left")
        .withColumn("participant_company_cnpj_root",
          when(col("participant_type_code") === "1" &&
            col("participant_identifier_raw").rlike("^[0-9A-Z]{12}[0-9]{2}$"),
            substring(col("participant_identifier_raw"), 1, 8)))
        .withColumn("entry_date",
          when(col("_entry_date_quality_reason").isNull, col("_entry_date_parsed"))
            .otherwise(lit(null).cast("date")))
        .withColumn("relationship_class", relationshipClass(col("participant_qualification_description")))
        .withColumn("relationship_rule_version", lit(RelationshipRuleVersion))
        .withColumn("privacy_class",
          when(col("participant_type_code") === "2", lit("MASKED_NATURAL_PERSON"))
            .when(col("participant_type_code") === "3", lit("FOREIGN_PARTICIPANT"))
            .otherwise(lit("LEGAL_ENTITY")))
        .withColumn("silver_transformation_timestamp", current_timestamp())
        .drop("_entry_date_parsed", "_entry_date_quality_reason")
      accepted.write.mode("overwrite").parquet(CompanyDataPaths.silverPartnersCandidate(config).toString)
      spark.read.parquet(CompanyDataPaths.silverPartnersCandidate(config).toString)
    } finally candidate.unpersist()
  }

  private[atlas] def buildRelationships(
      spark: SparkSession,
      config: AtlasConfig,
      partners: DataFrame,
      companies: DataFrame
  ): DataFrame = {
    val targets = companies.select(col("cnpj_root").as("_target_root"))
    val resolved = partners
      .filter(col("participant_type_code") === "1" && col("participant_company_cnpj_root").isNotNull)
      .join(targets, col("participant_company_cnpj_root") === col("_target_root"), "left")
      .withColumn("resolution_status",
        when(col("_target_root").isNotNull, lit("RESOLVED_ATLAS_COMPANY")).otherwise(lit("UNRESOLVED_COMPANY")))
      .withColumn("resolution_method",
        when(col("_target_root").isNotNull, lit("STRUCTURAL_CNPJ_ROOT_EXACT")).otherwise(lit("NONE")))
    writeDiagnostic(
      resolved.filter(col("resolution_status") =!= "RESOLVED_ATLAS_COMPANY"),
      CompanyDataPaths.qualityRoot(config).resolve("unresolved_legal_entity_partners")
    )
    val relationshipEvidence = resolved.filter(col("resolution_status") === "RESOLVED_ATLAS_COMPANY")
      .withColumn("relationship_edge_id", sha2(concat_ws("|",
        col("source_company_cnpj_root"), col("participant_company_cnpj_root"),
        col("relationship_class"), col("participant_qualification_code"),
        col("relationship_rule_version")), 256))
      .select(
        col("relationship_edge_id"), col("source_company_cnpj_root"),
        col("participant_company_cnpj_root"), col("relationship_class"),
        col("participant_qualification_code"), col("participant_qualification_description"),
        col("entry_date").as("source_reported_entry_date"), col("resolution_status"),
        col("resolution_method"), col("relationship_rule_version"), col("partner_record_id"),
        col("source_name"), col("source_file"), col("release"),
        lit("RECEITA_QSA").as("evidence_source"), lit("SOURCE_EVIDENCED").as("confidence"),
        current_timestamp().as("silver_transformation_timestamp")
      )
    val relationships = relationshipEvidence.groupBy(
      "relationship_edge_id", "source_company_cnpj_root", "participant_company_cnpj_root",
      "relationship_class", "participant_qualification_code",
      "participant_qualification_description", "resolution_status", "resolution_method",
      "relationship_rule_version", "source_name", "release", "evidence_source", "confidence"
    ).agg(
      min("source_reported_entry_date").as("source_reported_entry_date"),
      min("partner_record_id").as("partner_record_id"),
      min("source_file").as("source_file"),
      min("silver_transformation_timestamp").as("silver_transformation_timestamp"),
      count(lit(1)).as("source_evidence_count")
    )
    relationships.write.mode("overwrite").parquet(CompanyDataPaths.silverRelationshipsCandidate(config).toString)
    spark.read.parquet(CompanyDataPaths.silverRelationshipsCandidate(config).toString)
  }

  private def writeRelationshipHistory(
      spark: SparkSession,
      config: AtlasConfig,
      current: DataFrame
  ): Unit = {
    val previousPath = CompanyDataPaths.silverRelationships(config)
    val previous = if (Files.exists(previousPath)) Some(spark.read.parquet(previousPath.toString)) else None
    val observationRoot = CompanyDataPaths.relationshipObservations(config).getParent
    val priorObservationSummary = if (Files.isDirectory(observationRoot)) {
      Some(spark.read.parquet(observationRoot.toString)
        .filter(col("observation_status") =!= "NO_LONGER_OBSERVED")
        .groupBy("relationship_edge_id").agg(
          min("release").as("_first_observed_release"),
          max("release").as("_last_observed_release")
        ))
    } else None
    val observed = previous match {
      case None => current.withColumn("observation_status", lit("FIRST_OBSERVED"))
      case Some(prior) =>
        current.join(prior.select(col("relationship_edge_id").as("_prior_edge")), 
          col("relationship_edge_id") === col("_prior_edge"), "left")
          .withColumn("observation_status",
            when(col("_prior_edge").isNull, lit("FIRST_OBSERVED")).otherwise(lit("STILL_OBSERVED")))
          .drop("_prior_edge")
    }
    val removed = previous.map(_.join(current.select(col("relationship_edge_id").as("_current_edge")),
      col("relationship_edge_id") === col("_current_edge"), "left_anti")
      .withColumn("release", lit(config.receita.snapshot))
      .withColumn("observation_status", lit("NO_LONGER_OBSERVED"))).getOrElse(observed.limit(0))
    val combined = observed.unionByName(removed, allowMissingColumns = true)
    val observations = priorObservationSummary.map(summary =>
      combined.join(summary, Seq("relationship_edge_id"), "left")
        .withColumn("first_observed_release",
          coalesce(col("_first_observed_release"), col("release")))
        .withColumn("last_observed_release",
          when(col("observation_status") === "NO_LONGER_OBSERVED",
            coalesce(col("_last_observed_release"), col("release")))
            .otherwise(col("release")))
        .drop("_first_observed_release", "_last_observed_release")
    ).getOrElse(
      combined.withColumn("first_observed_release", col("release"))
        .withColumn("last_observed_release", col("release"))
    )
      .withColumn("observation_id", sha2(concat_ws("|", col("relationship_edge_id"),
        col("release"), col("observation_status")), 256))
      .withColumn("observed_at", current_timestamp())
    observations.write.mode("overwrite").parquet(CompanyDataPaths.relationshipObservations(config).toString)
    current.write.mode("overwrite").parquet(CompanyDataPaths.silverRelationships(config).toString)
  }

  private[atlas] def buildProfiles(
      config: AtlasConfig,
      companies: DataFrame,
      establishments: DataFrame,
      tax: DataFrame,
      relationships: DataFrame
  ): DataFrame = {
    val establishmentSummary = establishments.groupBy("cnpj_root").agg(
      count(lit(1)).as("establishment_count"),
      sum(when(col("is_active"), lit(1L)).otherwise(lit(0L))).as("active_establishment_count"),
      max(when(col("is_headquarters"), col("cnpj_full"))).as("headquarters_cnpj_full")
    )
    val relationshipSummary = relationships.groupBy("source_company_cnpj_root").agg(
      countDistinct("relationship_edge_id").as("immediate_corporate_participant_count")
    )
    companies.join(establishmentSummary, Seq("cnpj_root"), "left")
      .join(tax.select("cnpj_root", "is_simples", "simples_option_date", "simples_exclusion_date",
        "is_mei", "mei_option_date", "mei_exclusion_date"), Seq("cnpj_root"), "left")
      .join(relationshipSummary,
        col("cnpj_root") === col("source_company_cnpj_root"), "left")
      .drop("source_company_cnpj_root")
      .na.fill(0L, Seq("establishment_count", "active_establishment_count",
        "immediate_corporate_participant_count"))
      .withColumn("product_release", lit(config.receita.snapshot))
      .withColumn("relationship_rule_version", lit(RelationshipRuleVersion))
      .withColumn("gold_calculation_timestamp", current_timestamp())
  }

  private[atlas] def buildNetwork(
      spark: SparkSession,
      config: AtlasConfig,
      relationships: DataFrame
  ): DataFrame = {
    val previousBroadcastThreshold = spark.conf.getOption("spark.sql.autoBroadcastJoinThreshold")
    spark.conf.set("spark.sql.autoBroadcastJoinThreshold", "-1")
    val edges = relationships.select(
      col("source_company_cnpj_root").as("source"),
      col("participant_company_cnpj_root").as("target"),
      col("relationship_edge_id"), col("relationship_class"),
      col("participant_qualification_code"), col("participant_qualification_description"),
      col("evidence_source"), col("confidence"), col("relationship_rule_version")
    ).dropDuplicates("relationship_edge_id").persist(StorageLevel.DISK_ONLY)
    try {
      val undirected = edges.select(col("source").as("node"), col("target").as("neighbor"))
        .union(edges.select(col("target").as("node"), col("source").as("neighbor")))
        .dropDuplicates("node", "neighbor")
      val initialLabels = undirected.select(col("node")).union(
        undirected.select(col("neighbor").as("node"))
      ).distinct().withColumn("component_id", col("node"))
      val labels = calculateComponentLabels(spark, config, undirected, initialLabels)
      val componentMetrics = labels.groupBy("component_id").agg(count(lit(1)).as("component_node_count"))
      val edgeComponents = edges.join(
        labels.select(col("node").as("source"), col("component_id")), Seq("source")
      )
      val componentEdges = edgeComponents.groupBy("component_id")
        .agg(count(lit(1)).as("component_edge_count"))
      val degreeOut = edges.groupBy("source").agg(count(lit(1)).as("source_out_degree"))
      val degreeIn = edges.groupBy("target").agg(count(lit(1)).as("target_in_degree"))
      var reach = edges.select(
        col("source").as("path_start"), col("target").as("path_end"),
        array(col("source"), col("target")).as("nodes"),
        array(col("relationship_edge_id")).as("edge_ids"), lit(1).as("path_depth")
      )
      var frontier = reach
      var depth = 1
      var cycleNodes = edges.filter(col("source") === col("target"))
        .select(col("source").as("cycle_node"))
      while (depth < MaximumCycleSearchDepth) {
        val extended = frontier.as("p").join(edges.as("e"), col("p.path_end") === col("e.source"))
          .select(
            col("p.path_start"), col("e.target").as("path_end"),
            concat(col("p.nodes"), array(col("e.target"))).as("nodes"),
            concat(col("p.edge_ids"), array(col("e.relationship_edge_id"))).as("edge_ids"),
            lit(depth + 1).as("path_depth")
          )
        cycleNodes = cycleNodes.union(
          extended.filter(col("path_start") === col("path_end"))
            .select(col("path_start").as("cycle_node"))
        ).distinct()
        frontier = extended.filter(col("path_start") =!= col("path_end") &&
          !array_contains(expr("slice(nodes, 1, size(nodes) - 1)"), col("path_end")))
        if (depth < MaximumMaterializedPathDepth) reach = reach.unionByName(frontier)
        depth += 1
      }
      reach.filter(col("path_start") =!= col("path_end"))
        .withColumn("path_id", sha2(concat_ws("|", col("path_start"), col("path_end"),
          col("path_depth"), concat_ws(">", col("edge_ids"))), 256))
        .withColumn("graph_calculation_version", lit(GraphCalculationVersion))
        .withColumn("product_release", lit(config.receita.snapshot))
        .write.mode("overwrite").parquet(CompanyDataPaths.goldRelationshipPaths(config).toString)
      val network = edgeComponents.join(degreeOut, Seq("source"), "left")
        .join(degreeIn, Seq("target"), "left")
        .join(componentMetrics, Seq("component_id"))
        .join(componentEdges, Seq("component_id"))
        .join(cycleNodes.select(col("cycle_node").as("source")).withColumn("source_in_cycle", lit(true)),
          Seq("source"), "left")
        .join(cycleNodes.select(col("cycle_node").as("target")).withColumn("target_in_cycle", lit(true)),
          Seq("target"), "left")
        .na.fill(false, Seq("source_in_cycle", "target_in_cycle"))
        .withColumn("edge_in_cycle", col("source_in_cycle") && col("target_in_cycle"))
        .withColumn("graph_calculation_version", lit(GraphCalculationVersion))
        .withColumn("product_release", lit(config.receita.snapshot))
        .withColumn("gold_calculation_timestamp", current_timestamp())
      val materializedNetwork = CompanyDataPaths.workRoot(config).resolve("graph-network")
      network.write.mode("overwrite").parquet(materializedNetwork.toString)
      spark.read.parquet(materializedNetwork.toString)
    } finally {
      edges.unpersist()
      previousBroadcastThreshold match {
        case Some(value) => spark.conf.set("spark.sql.autoBroadcastJoinThreshold", value)
        case None => spark.conf.unset("spark.sql.autoBroadcastJoinThreshold")
      }
    }
  }

  private def calculateComponentLabels(
      spark: SparkSession,
      config: AtlasConfig,
      undirected: DataFrame,
      initialLabels: DataFrame
  ): DataFrame = {
    val maximumRounds = config.graph.maxComponentPropagationRounds
    var labels = initialLabels
    var round = 1
    var changedCount = Long.MaxValue

    def propagate(): Unit = {
      val propagated = undirected.join(
        labels.select(col("node").as("neighbor"), col("component_id").as("neighbor_component")),
        Seq("neighbor")
      ).groupBy("node").agg(min("neighbor_component").as("propagated_component"))
      val next = labels.join(propagated, Seq("node"), "left")
        .select(col("node"), least(col("component_id"),
          coalesce(col("propagated_component"), col("component_id"))).as("component_id"))
      val iterationPath = CompanyDataPaths.workRoot(config)
        .resolve("graph-component-labels").resolve(f"iteration=${round - 1}%03d")
      next.write.mode("overwrite").parquet(iterationPath.toString)
      val materialized = spark.read.parquet(iterationPath.toString)
      changedCount = materialized.except(labels).count()
      labels = materialized
      println(s"Corporate component progress: release=${config.receita.snapshot} " +
        s"round=$round changed_nodes=$changedCount")
      round += 1
    }

    while (round <= maximumRounds && changedCount > 0) propagate()
    if (changedCount > 0) propagate()
    if (changedCount > 0) {
      val artifacts = CompanyDataPaths.workRoot(config).resolve("graph-component-labels")
      throw new IllegalStateException(
        s"Corporate component calculation for release ${config.receita.snapshot} did not stabilize " +
          s"after $maximumRounds propagation rounds; $changedCount node labels changed in the " +
          s"confirmation round; iteration artifacts: $artifacts"
      )
    }
    labels
  }

  private[atlas] def buildLeads(
      spark: SparkSession,
      config: AtlasConfig,
      companies: DataFrame,
      establishments: DataFrame,
      geography: DataFrame
  ): DataFrame = {
    val taxonomy = ConfigFactory.parseFile(new File("conf/cnae-groups.conf")).resolve()
    val version = taxonomy.getString("cnae-groups.version")
    val groups = taxonomy.getConfig("cnae-groups.groups")
    val memberships = groups.root().keySet().asScala.toSeq.sorted.flatMap { group =>
      groups.getStringList(group).asScala.map(code => (group, code))
    }
    import spark.implicits._
    val membership = memberships.toDF("business_group", "matched_cnae")
    val expanded = establishments.filter(col("is_active") === true)
      .select(
        col("*"),
        explode(array_union(
          when(col("main_cnae").isNull, typedLit(Seq.empty[String]))
            .otherwise(array(col("main_cnae"))),
          coalesce(col("secondary_cnaes"), typedLit(Seq.empty[String]))
        )).as("candidate_cnae")
      )
    expanded.join(membership, col("candidate_cnae") === col("matched_cnae"))
      .join(companies.select("cnpj_root", "legal_name"), Seq("cnpj_root"))
      .join(geography, col("municipality_code") === col("receita_municipality_code"))
      .select(
        col("cnpj_full"), col("cnpj_root"), col("legal_name"), col("trade_name"),
        col("is_headquarters"), col("opening_date"), col("registration_status_code"),
        col("main_cnae"), col("secondary_cnaes"), col("matched_cnae"),
        when(col("matched_cnae") === col("main_cnae"), lit("PRIMARY")).otherwise(lit("SECONDARY"))
          .as("cnae_match_source"),
        col("business_group"), lit(version).as("taxonomy_version"),
        col("receita_municipality_code"), col("ibge_municipality_code"),
        col("ibge_municipality_name"), col("state_abbreviation"), col("region_name"),
        lit(config.receita.snapshot).as("product_release"),
        current_timestamp().as("gold_calculation_timestamp")
      ).dropDuplicates("cnpj_full", "business_group")
  }

  private def extractedInputs(config: AtlasConfig, name: String): Seq[Path] = {
    val path = CompanyDataPaths.extractedRoot(config).resolve(name)
    if (!Files.isDirectory(path)) throw new IllegalArgumentException(s"Missing extracted $name inputs: $path")
    val stream = Files.list(path)
    try stream.iterator().asScala.filter(Files.isRegularFile(_)).toSeq.sortBy(_.toString)
    finally stream.close()
  }

  private def readCsv(
      spark: SparkSession,
      inputs: Seq[Path],
      schema: org.apache.spark.sql.types.StructType
  ): DataFrame =
    spark.read.schema(schema).option("header", "false").option("sep", ";")
      .option("encoding", "ISO-8859-1").option("quote", "\"").option("escape", "\"")
      .csv(inputs.map(_.toString): _*)

  private def nullable(value: Column): Column =
    when(length(trim(value)) === 0, lit(null).cast("string")).otherwise(trim(value))

  private def root(value: Column): Column =
    upper(regexp_replace(nullable(value), "[./-]", ""))

  private def indicator(value: Column): Column =
    when(value === "S", lit(true)).when(value === "N", lit(false)).otherwise(lit(null).cast("boolean"))

  private def sourceDate(value: Column): Column =
    when(value.rlike("^[0-9]{8}$"), to_date(value, "yyyyMMdd"))

  private def invalidSourceDate(value: Column): Column =
    value.isNotNull && (!value.rlike("^[0-9]{8}$") || to_date(value, "yyyyMMdd").isNull)

  private def partnerDateQualityReason(value: Column, parsed: Column, release: String): Column = {
    val releaseEnd = YearMonth.parse(release).atEndOfMonth()
    when(value.isNull, lit(null).cast("string"))
      .when(!value.rlike("^[0-9]{8}$"), lit("invalid_date_format"))
      .when(parsed.isNull, lit("invalid_calendar_date"))
      .when(parsed < lit(EarliestSupportedPartnerDate.toString).cast("date"),
        lit("date_before_supported_minimum"))
      .when(parsed > lit(releaseEnd.toString).cast("date"), lit("date_after_release"))
      .otherwise(lit(null).cast("string"))
  }

  private def relationshipClass(description: Column): Column = {
    val upperDescription = upper(coalesce(description, lit("")))
    when(upperDescription.contains("REPRESENTANTE"), lit("LEGAL_REPRESENTATION"))
      .when(upperDescription.contains("ADMINISTRADOR") || upperDescription.contains("ADMINISTRAÇÃO"),
        lit("PARTNER_ADMINISTRATION"))
      .when(upperDescription.contains("DIRETOR") || upperDescription.contains("PRESIDENTE") ||
        upperDescription.contains("CONSELHEIRO"), lit("MANAGEMENT"))
      .when(upperDescription.contains("SÓCIO") || upperDescription.contains("SOCIO") ||
        upperDescription.contains("TITULAR") || upperDescription.contains("ACIONISTA"),
        lit("OWNERSHIP_OR_PARTNERSHIP_INTEREST"))
      .otherwise(lit("UNKNOWN_CORPORATE_RELATIONSHIP"))
  }

  private def writeDiagnostic(frame: DataFrame, path: Path): Unit =
    if (frame.limit(1).count() > 0) frame.write.mode("overwrite").parquet(path.toString)
}
