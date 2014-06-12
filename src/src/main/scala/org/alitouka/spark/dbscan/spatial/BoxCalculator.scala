package org.alitouka.spark.dbscan.spatial

import org.alitouka.spark.dbscan.{BoxId, DbscanSettings, RawDataSet}
import org.alitouka.spark.dbscan.spatial.rdd.{BoxPartitioner, PartitioningSettings}
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext._

/** Calculates box-shaped regions for density-based partitioning (see [[org.alitouka.spark.dbscan.spatial.rdd.BoxPartitioner]] )
  * and for fast lookup of point's neighbors (see [[org.alitouka.spark.dbscan.spatial.PartitionIndex]]
  *
  * @param data A raw data set
  */
private [dbscan] class BoxCalculator (val data: RawDataSet) {

  val numberOfDimensions: Int = getNumberOfDimensions (data)

  def generateDensityBasedBoxes (partitioningSettings: PartitioningSettings = new PartitioningSettings (),
                                 dbscanSettings: DbscanSettings = new DbscanSettings ()): (Iterable[Box], Box) = {

    val datasetBounds = calculateBounds(data, numberOfDimensions)
    val rootBox = new Box (datasetBounds.toArray)
    val boxTree = BoxCalculator.generateTreeOfBoxes(rootBox, partitioningSettings, dbscanSettings)

    val broadcastBoxTree = data.sparkContext.broadcast(boxTree)

    val partialCounts: RDD[(BoxId, Long)] = data.mapPartitions {
      it => {
        val bt = broadcastBoxTree.value.clone ()
        BoxCalculator.countPointsInOnePartition(bt, it)
      }
    }

    val totalCounts = partialCounts.foldByKey(0)(_+_).collectAsMap()
    val boxesWithEnoughPoints = boxTree.flattenBoxes {
      x => totalCounts (x.box.boxId) >= partitioningSettings.numberOfPointsInBox
    }

    (BoxPartitioner.assignPartitionIdsToBoxes(boxesWithEnoughPoints), rootBox)
  }


  private [dbscan] def getNumberOfDimensions (ds: RawDataSet): Int = {
    val pt = ds.take(1)(0)
    pt.coordinates.length
  }

  def calculateBoundingBox: Box = new Box (calculateBounds (data, numberOfDimensions).toArray)

  private [dbscan] def calculateBounds (ds: RawDataSet, dimensions: Int): List[BoundsInOneDimension] = {
    val minPoint = new Point (Array.fill (dimensions)(Double.MaxValue))
    val maxPoint = new Point (Array.fill (dimensions)(Double.MinValue))

    val mins = fold (ds, minPoint, x => Math.min (x._1, x._2))
    val maxs = fold (ds, maxPoint, x => Math.max (x._1, x._2))

    mins.coordinates.zip (maxs.coordinates).map ( x => new BoundsInOneDimension (x._1, x._2, true) ).toList
  }

  private def fold (ds: RawDataSet, zeroValue: Point, mapFunction: ((Double, Double)) => Double) = {
    ds.fold(zeroValue) {
      (pt1, pt2) => {
        new Point (pt1.coordinates.zip (pt2.coordinates).map ( mapFunction ).toArray)
      }
    }
  }
}

private [dbscan] object BoxCalculator {

  def generateTreeOfBoxes (root: Box,
                           partitioningSettings: PartitioningSettings,
                           dbscanSettings: DbscanSettings): BoxTreeItemWithNumberOfPoints = {
    BoxCalculator.generateTreeOfBoxes(root, partitioningSettings, dbscanSettings, new BoxIdGenerator(root.boxId))
  }


  def generateTreeOfBoxes (root: Box,
                           partitioningSettings: PartitioningSettings,
                           dbscanSettings: DbscanSettings,
                           idGenerator: BoxIdGenerator): BoxTreeItemWithNumberOfPoints = {

    val result = new BoxTreeItemWithNumberOfPoints(root)

    result.children = if (partitioningSettings.numberOfLevels > 0) {

      val newPartitioningSettings = partitioningSettings.withNumberOfLevels(partitioningSettings.numberOfLevels-1)

      root
        .splitAlongLongestDimension(partitioningSettings.numberOfSplits, idGenerator)
        .filter(_.isBigEnough(dbscanSettings))
        .map(x => generateTreeOfBoxes(x,
          newPartitioningSettings,
          dbscanSettings,
          idGenerator))
        .toList
    }
    else {
      List[BoxTreeItemWithNumberOfPoints] ()
    }

    result
  }

  def countOnePoint (pt: Point, root: BoxTreeItemWithNumberOfPoints): Unit = {

    if (root.box.isPointWithin(pt)) {
      root.numberOfPoints += 1

      root.children.foreach {
        x => BoxCalculator.countOnePoint(pt, x)
      }
    }
  }

  def countPointsInOnePartition (root: BoxTreeItemWithNumberOfPoints, it: Iterator[Point]): Iterator[(BoxId, Long)] = {
    it.foreach (pt => BoxCalculator.countOnePoint (pt, root))
    root.flatten.map {
      x: BoxTreeItemWithNumberOfPoints => { (x.box.boxId, x.numberOfPoints) }
    }.iterator
  }

  private [dbscan] def generateCombinationsOfSplits (splits: List[List[BoundsInOneDimension]],
                                                     dimensionIndex: Int): List[List[BoundsInOneDimension]] = {

    if (dimensionIndex < 0) {
      List(List())
    }
    else {
      for {
        i <- BoxCalculator.generateCombinationsOfSplits(splits, dimensionIndex - 1)
        j <- splits(dimensionIndex)
      }
      yield j :: i
    }
  }

  def splitBoxIntoEqualBoxes (rootBox: Box, maxSplits: Int, dbscanSettings: DbscanSettings): Iterable[Box] = {

    val dimensions = rootBox.bounds.size
    val splits = rootBox.bounds.map ( _.split(maxSplits, dbscanSettings) )
    val combinations = BoxCalculator.generateCombinationsOfSplits(splits.toList, dimensions-1)

    for (i <- 0 until combinations.size) yield new Box (combinations(i).reverse , i+1)
  }
}