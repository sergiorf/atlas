package atlas.status

import java.time.Instant

final case class SnapshotSummary(
    snapshot: String,
    publicationStatus: Option[String],
    successfulStages: Int,
    warningStages: Int,
    failedStages: Int,
    otherStages: Int,
    warningTypeCount: Int,
    lastRecorded: Instant
)

final case class DatasetStageSummary(
    dataset: String,
    raw: Option[String],
    bronze: Option[String],
    silver: Option[String],
    history: Option[String],
    publication: Option[String]
)

final case class StatusProblem(
    dataset: String,
    stage: String,
    status: String,
    warning: Option[QualityWarning],
    errorMessage: Option[String]
)

final case class StatusOverview(
    snapshots: Seq[SnapshotSummary],
    selectedSnapshot: String,
    datasets: Seq[DatasetStageSummary],
    problems: Seq[StatusProblem]
)

object StatusOverview {
  def build(statuses: Seq[RunStatus], release: Option[String]): StatusOverview = {
    require(statuses.nonEmpty, "Cannot build a status overview from an empty registry")
    val available = statuses.map(_.snapshot).distinct.sorted(Ordering.String.reverse)
    val selected = release match {
      case Some(value) if !available.contains(value) =>
        throw new IllegalArgumentException(
          s"No status records found for snapshot $value. Available snapshots: ${available.sorted.mkString(", ")}"
        )
      case Some(value) => value
      case None        => available.head
    }
    val included = release.fold(statuses)(value => statuses.filter(_.snapshot == value))
    StatusOverview(
      snapshots = included.groupBy(_.snapshot).toSeq.sortBy(_._1)(Ordering.String.reverse).map {
        case (snapshot, records) => summarizeSnapshot(snapshot, records)
      },
      selectedSnapshot = selected,
      datasets = summarizeDatasets(statuses.filter(_.snapshot == selected)),
      problems = summarizeProblems(statuses.filter(_.snapshot == selected))
    )
  }

  private def summarizeSnapshot(snapshot: String, records: Seq[RunStatus]): SnapshotSummary = {
    val pipeline = records.filterNot(_.layer == "bundle")
    val known = pipeline.groupBy(_.status).map { case (status, values) => status -> values.size }
    SnapshotSummary(
      snapshot = snapshot,
      publicationStatus =
        records.filter(_.layer == "bundle").sortBy(_.finishedAt).lastOption.map(_.status),
      successfulStages = known.getOrElse("success", 0),
      warningStages = known.getOrElse("success_with_warnings", 0),
      failedStages = known.getOrElse("failed", 0),
      otherStages = pipeline.count(status =>
        !Set("success", "success_with_warnings", "failed").contains(status.status)
      ),
      warningTypeCount = pipeline.flatMap(_.qualityWarnings.map(_.warningType)).distinct.size,
      lastRecorded = records.map(_.finishedAt).max
    )
  }

  private def summarizeDatasets(records: Seq[RunStatus]): Seq[DatasetStageSummary] = {
    val componentRows = records
      .filterNot(_.layer == "bundle")
      .groupBy(componentLabel)
      .map { case (dataset, values) =>
        DatasetStageSummary(
          dataset,
          stageStatus(values, "raw"),
          stageStatus(values, "bronze"),
          stageStatus(values, "silver"),
          stageStatus(values, "history"),
          None
        )
      }
      .toSeq
    val bundleRows = records
      .filter(_.layer == "bundle")
      .groupBy(_ => "atomic bundle")
      .map { case (dataset, values) =>
        DatasetStageSummary(
          dataset,
          None,
          None,
          None,
          None,
          values.sortBy(_.finishedAt).lastOption.map(s => displayStatus(s.status))
        )
      }
      .toSeq
    (componentRows ++ bundleRows).sortBy(row => datasetOrder(row.dataset) -> row.dataset)
  }

  private def summarizeProblems(records: Seq[RunStatus]): Seq[StatusProblem] = {
    val failures = records.filter(_.status == "failed").map { status =>
      StatusProblem(
        problemLabel(status),
        displayStage(status.layer),
        "failed",
        None,
        status.errorMessage
      )
    }
    val warningDetails = records.flatMap { status =>
      status.qualityWarnings.map { warning =>
        StatusProblem(
          problemLabel(status),
          displayStage(status.layer),
          "warning",
          Some(warning),
          None
        )
      }
    }
    val warnings = warningDetails
      .groupBy { problem =>
        val warning = problem.warning.get
        (problem.dataset, warning.warningType, warning.rowCount, warning.reportPath)
      }
      .values
      .map(_.minBy(problem => stageOrder(problem.stage)))
      .toSeq
    val warningsWithoutDetails = records
      .filter(status => status.status == "success_with_warnings" && status.qualityWarnings.isEmpty)
      .map(status =>
        StatusProblem(problemLabel(status), displayStage(status.layer), "warning", None, None)
      )
    (failures ++ warnings ++ warningsWithoutDetails).sortBy(problem =>
      (
        datasetOrder(problem.dataset),
        problem.dataset,
        stageOrder(problem.stage),
        problem.warning.map(_.warningType).getOrElse("")
      )
    )
  }

  private def stageStatus(records: Seq[RunStatus], stage: String): Option[String] =
    records
      .filter(_.layer == stage)
      .sortBy(_.finishedAt)
      .lastOption
      .map(s => displayStatus(s.status))

  private def displayStatus(status: String): String = status match {
    case "success"               => "ok"
    case "success_with_warnings" => "warning"
    case value                   => value
  }

  private def componentLabel(status: RunStatus): String =
    if (status.dataset == "company-data" && status.layer == "raw") "company source package"
    else displayDataset(status.dataset)

  private def problemLabel(status: RunStatus): String =
    if (status.layer == "bundle") "atomic bundle" else componentLabel(status)

  private def displayDataset(dataset: String): String = dataset match {
    case "estabelecimentos" | "establishments" | "estabelecimentos_history" => "establishments"
    case value                                                              => value
  }

  private def displayStage(stage: String): String = if (stage == "bundle") "publication" else stage

  private def datasetOrder(dataset: String): Int = dataset match {
    case "company source package" => 0
    case "companies"              => 1
    case "establishments"         => 2
    case "company-references"     => 3
    case "municipality-geography" => 4
    case "atomic bundle"          => 5
    case _                        => 6
  }

  private def stageOrder(stage: String): Int = stage match {
    case "raw"         => 0
    case "bronze"      => 1
    case "silver"      => 2
    case "history"     => 3
    case "publication" => 4
    case _             => 5
  }
}
