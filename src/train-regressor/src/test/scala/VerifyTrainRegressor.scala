// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.io.File

import org.apache.spark.ml.Estimator
import org.apache.spark.ml.regression.{LinearRegression, RandomForestRegressor}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types._
import org.apache.commons.io.FileUtils

/** Tests to validate the functionality of Train Regressor module. */
class VerifyTrainRegressor extends EstimatorFuzzingTest {

  val regressionTrainFilesDirectory = "/Regression/Train/"

  val mockLabelColumn = "Label"

  def createMockDataset: DataFrame = {
    session.createDataFrame(Seq(
      (0, 2, 0.50, 0.60, 0),
      (1, 3, 0.40, 0.50, 1),
      (2, 4, 0.78, 0.99, 2),
      (3, 5, 0.12, 0.34, 3),
      (0, 1, 0.50, 0.60, 0),
      (1, 3, 0.40, 0.50, 1),
      (2, 3, 0.78, 0.99, 2),
      (3, 4, 0.12, 0.34, 3),
      (0, 0, 0.50, 0.60, 0),
      (1, 2, 0.40, 0.50, 1),
      (2, 3, 0.78, 0.99, 2),
      (3, 4, 0.12, 0.34, 3)))
      .toDF(mockLabelColumn, "col1", "col2", "col3", "col4")
  }

  test("Smoke test for training on a regressor") {
    val dataset = createMockDataset

    val linearRegressor = TrainRegressorTestUtilities.createLinearRegressor(mockLabelColumn)

    TrainRegressorTestUtilities.trainScoreDataset(mockLabelColumn, dataset, linearRegressor)
  }

  test("Verify you can score on a dataset without a label column") {
    val dataset: DataFrame = createMockDataset

    val linearRegressor = TrainRegressorTestUtilities.createLinearRegressor(mockLabelColumn)

    val data = dataset.randomSplit(Seq(0.6, 0.4).toArray, 42)
    val trainData = data(0)
    val testData = data(1)

    val model = linearRegressor.fit(trainData)

    model.transform(testData.drop(mockLabelColumn))
  }

  test("Verify train regressor works with different output types") {
    val dataset = createMockDataset
    val castLabelCol = "cast_" + mockLabelColumn
    for (outputType <-
         Seq(IntegerType, LongType, ByteType, BooleanType, FloatType, DoubleType, ShortType)) {
      val modifiedDataset = dataset.withColumn(castLabelCol, dataset(mockLabelColumn).cast(outputType))
      val linearRegressor = TrainRegressorTestUtilities.createLinearRegressor(castLabelCol)
      TrainRegressorTestUtilities.trainScoreDataset(castLabelCol, modifiedDataset, linearRegressor)
    }
  }

  test("Verify a trained regression model can be saved") {
    val dataset: DataFrame = createMockDataset

    val linearRegressor = TrainRegressorTestUtilities.createLinearRegressor(mockLabelColumn)

    val model = linearRegressor.fit(dataset)

    val myModelName = "testModel"
    lazy val dir = new File(myModelName)
    try {
      model.write.overwrite().save(myModelName)
      // write a second time with overwrite flag, verify still works
      model.write.overwrite().save(myModelName)
      // assert directory exists
      assert(dir.exists())

      // load the model
      val loadedModel = TrainedRegressorModel.load(myModelName)

      // verify model data loaded
      assert(loadedModel.labelColumn == model.labelColumn)
      assert(loadedModel.uid == model.uid)
      val transformedDataset = loadedModel.transform(dataset)
      val benchmarkDataset = model.transform(dataset)
      assert(verifyResult(transformedDataset, benchmarkDataset))
    } finally {
      // delete the file to cleanup
      FileUtils.forceDelete(dir)
    }
  }

  test("Verify regressor can be trained and scored on airfoil_self_noise-train-csv") {
    val fileLocation =
      sys.env("DATASETS_HOME") + regressionTrainFilesDirectory + "airfoil_self_noise.train.csv"
    val dataset = session.read.format("com.databricks.spark.csv")
      .option("header", "true").option("inferSchema", "true")
      .option("delimiter", ",").option("treatEmptyValuesAsNulls", "false")
      .load(fileLocation)

    val labelColumn = "Scaled sound pressure level"

    val linearRegressor = TrainRegressorTestUtilities.createLinearRegressor(labelColumn)

    TrainRegressorTestUtilities.trainScoreDataset(labelColumn, dataset, linearRegressor)
  }

  test("Verify regressor can be trained and scored on CASP-train-csv") {
    val fileLocation =
      sys.env("DATASETS_HOME") + regressionTrainFilesDirectory + "CASP.train.csv"
    val dataset = session.read.format("com.databricks.spark.csv")
      .option("header", "true").option("inferSchema", "true")
      .option("delimiter", ",").option("treatEmptyValuesAsNulls", "false")
      .load(fileLocation)

    val labelColumn = "RMSD"

    val parameters = TrainRegressorTestUtilities.createRandomForestRegressor(labelColumn)

    TrainRegressorTestUtilities.trainScoreDataset(labelColumn, dataset, parameters)
  }

  override def setParams(fitDataset: DataFrame, estimator: Estimator[_]): Estimator[_] =
    estimator.asInstanceOf[TrainRegressor].setModel(new LinearRegression()).setLabelCol(mockLabelColumn)

  override def createFitDataset: DataFrame = createMockDataset

  override def schemaForDataset: StructType = ???

  override def getEstimator(): Estimator[_] = new TrainRegressor()
}

/** Test helper methods for Train Regressor module. */
object TrainRegressorTestUtilities {

  def createLinearRegressor(labelColumn: String): TrainRegressor = {
    val linearRegressor = new LinearRegression()
      .setRegParam(0.3)
      .setElasticNetParam(0.8)
    val trainRegressor = new TrainRegressor()
    trainRegressor
      .setModel(linearRegressor)
      .set(trainRegressor.labelCol, labelColumn)
  }

  def createRandomForestRegressor(labelColumn: String): TrainRegressor = {
    val linearRegressor = new RandomForestRegressor()
      .setFeatureSubsetStrategy("auto")
      .setMaxBins(32)
      .setMaxDepth(5)
      .setMinInfoGain(0.0)
      .setMinInstancesPerNode(1)
      .setNumTrees(20)
    val trainRegressor = new TrainRegressor()
    trainRegressor
      .setModel(linearRegressor)
      .set(trainRegressor.labelCol, labelColumn)
  }

  def trainScoreDataset(labelColumn: String, dataset: DataFrame, trainRegressor: Estimator[TrainedRegressorModel])
      : DataFrame = {
    val data = dataset.randomSplit(Seq(0.6, 0.4).toArray, 42)
    val trainData = data(0)
    val testData = data(1)

    val model = trainRegressor.fit(trainData)
    val scoredData = model.transform(testData)
    scoredData
  }

}
