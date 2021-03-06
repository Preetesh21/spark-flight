import org.apache.spark._
import org.apache.spark.sql.SQLContext
import org.apache.spark.ml.feature.{StringIndexer, VectorAssembler, OneHotEncoder}
import org.apache.spark.ml.tuning.{ParamGridBuilder, TrainValidationSplit}
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.regression.{LinearRegression, RandomForestRegressor, GBTRegressor}
import org.apache.spark.ml.Pipeline
import org.apache.spark.mllib.evaluation.RegressionMetrics
import org.apache.spark.sql.functions.col

object Flight extends App{

  def main(args: Array[String]) {
    print("Enter the path\n")
    val dataPath = scala.io.StdIn.readLine()
    var mlTechnique: Int = 0
    print("Enter the technique you want to be deployed [1]-Linear Regression [2]-RandomForest [3]-GBTRegressor\n")
    while(mlTechnique != 1 && mlTechnique != 2 && mlTechnique != 3){
      mlTechnique = readInt()
    }
    val useCategorical = true
    val conf = new SparkConf().setAppName("predictor").setMaster("local[*]")
    val sc = new SparkContext(conf)
    val sqlContext = new SQLContext(sc)
    val rawData = sqlContext.read.format("com.databricks.spark.csv")
      .option("header", "true")
      .option("inferSchema", "true")
      .load(dataPath)
      .withColumn("DelayOutputVar", col("ArrDelay").cast("double"))
      .withColumn("DepDelayDouble", col("DepDelay").cast("double"))
      .withColumn("TaxiOutDouble", col("TaxiOut").cast("double"))
      .cache()

    val data2 = rawData
      .drop("ActualElapsedTime") // Forbidden
      .drop("ArrTime") // Forbidden
      .drop("AirTime") // Forbidden
      .drop("TaxiIn") // Forbidden
      .drop("Diverted") // Forbidden
      .drop("CarrierDelay") // Forbidden
      .drop("WeatherDelay") // Forbidden
      .drop("NASDelay") // Forbidden
      .drop("SecurityDelay") // Forbidden
      .drop("LateAircraftDelay") // Forbidden
      .drop("DepDelay") // Casted to double in a new variable called DepDelayDouble
      .drop("TaxiOut") // Casted to double in a new variable called TaxiOutDouble
      .drop("UniqueCarrier") // Always the same value // Remove correlated variables
      .drop("CancellationCode") // Cancelled flights don't count
      .drop("DepTime") // Highly correlated to CRSDeptime
      .drop("CRSArrTime") // Highly correlated to CRSDeptime
      .drop("CRSElapsedTime") // Highly correlated to Distance
      .drop("Distance") // Remove uncorrelated variables to the arrDelay
      .drop("FlightNum") // Remove uncorrelated variables to the arrDelay
      .drop("CRSDepTime") // Remove uncorrelated variables to the arrDelay
      .drop("Year") // Remove uncorrelated variables to the arrDelay
      .drop("Month") // Remove uncorrelated variables to the arrDelay
      .drop("DayofMonth") // Remove uncorrelated variables to the arrDelay
      .drop("DayOfWeek") // Remove uncorrelated variables to the arrDelay
      .drop("TailNum")

    // remove cancelled flights
    val data = data2.filter("DelayOutputVar is not null")

    val assembler = new VectorAssembler()
      .setInputCols(Array("OriginVec", "DestVec", "DepDelayDouble", "TaxiOutDouble"))
      .setOutputCol("features")
      .setHandleInvalid("skip")

    val categoricalVariables = Array("Origin", "Dest")
    val categoricalIndexers = categoricalVariables.map(i => new StringIndexer().setInputCol(i).setOutputCol(i+"Index").setHandleInvalid("skip"))
    val categoricalEncoders = categoricalVariables.map(e => new OneHotEncoder().setInputCol(e + "Index").setOutputCol(e + "Vec").setDropLast(false))


    mlTechnique match {
      case 1 =>
        val lr = new LinearRegression()
          .setLabelCol("DelayOutputVar")
          .setFeaturesCol("features")
        val paramGrid = new ParamGridBuilder()
          .addGrid(lr.regParam, Array(0.1, 0.01))
          .addGrid(lr.fitIntercept)
          .addGrid(lr.elasticNetParam, Array(0.0, 1.0))
          .build()

        val steps:Array[org.apache.spark.ml.PipelineStage] = categoricalIndexers ++ categoricalEncoders ++ Array(assembler, lr)
        val pipeline = new Pipeline().setStages(steps)
        val tvs = new TrainValidationSplit()
          .setEstimator(pipeline)
          .setEvaluator(new RegressionEvaluator().setLabelCol("DelayOutputVar"))
          .setEstimatorParamMaps(paramGrid)
          .setTrainRatio(0.7)

        val Array(training, test) = data.randomSplit(Array(0.70, 0.30), seed = 12345)
        val model = tvs.fit(training)
        val holdout = model.transform(test).select("prediction", "DelayOutputVar")
        val rm = new RegressionMetrics(holdout.rdd.map(x =>
          (x(0).asInstanceOf[Double], x(1).asInstanceOf[Double])))

        println("sqrt(MSE): " + Math.sqrt(rm.meanSquaredError))
        println("mean absolute error: " + 	rm.meanAbsoluteError)
        println("R Squared: " + rm.r2)
        println("Explained Variance: " + rm.explainedVariance + "\n")

      case 2 =>
        val rf = new RandomForestRegressor()
          .setNumTrees(10)
          .setMaxDepth(10)
          .setLabelCol("DelayOutputVar")
          .setFeaturesCol("features")

        val steps:Array[org.apache.spark.ml.PipelineStage] = categoricalIndexers ++ categoricalEncoders ++ Array(assembler, rf)
        val pipeline = new Pipeline().setStages(steps)
        val Array(training, test) = data.randomSplit(Array(0.70, 0.30), seed = 12345)
        val model = pipeline.fit(training)
        val holdout = model.transform(test).select("prediction", "DelayOutputVar")
        val rm = new RegressionMetrics(holdout.rdd.map(x =>
          (x(0).asInstanceOf[Double], x(1).asInstanceOf[Double])))

        println("sqrt(MSE): " + Math.sqrt(rm.meanSquaredError))
        println("mean absolute error: " + 	rm.meanAbsoluteError)
        println("R Squared: " + rm.r2)
        println("Explained Variance: " + rm.explainedVariance + "\n")

      case _ =>
        val gbt = new GBTRegressor()
          .setLabelCol("DelayOutputVar")
          .setFeaturesCol("features")
          .setMaxIter(10)

        val steps:Array[org.apache.spark.ml.PipelineStage] = categoricalIndexers ++ categoricalEncoders ++ Array(assembler, gbt)
        val pipeline = new Pipeline().setStages(steps)
        val Array(training, test) = data.randomSplit(Array(0.70, 0.30), seed = 12345)
        val model = pipeline.fit(training)
        val holdout = model.transform(test).select("prediction", "DelayOutputVar")
        val rm = new RegressionMetrics(holdout.rdd.map(x =>
          (x(0).asInstanceOf[Double], x(1).asInstanceOf[Double])))

        println("sqrt(MSE): " + Math.sqrt(rm.meanSquaredError))
        println("mean absolute error: " + 	rm.meanAbsoluteError)
        println("R Squared: " + rm.r2)
        println("Explained Variance: " + rm.explainedVariance + "\n")

    }
    sc.stop()
  }

}

// Linear Regression - Only with "OriginVec", "DestVec", "DepDelayDouble", "TaxiOutDouble"
// sqrt(MSE): 12.713505977002903
// mean absolute error: 7.158456529716749
// R Squared:  0.8548584375831392
// Explained Variance: 1181.39053839548

// Random Forest with numTress=10 and depth=10 - Only with "OriginVec", "DestVec", "DepDelayDouble", "TaxiOutDouble"
// sqrt(MSE): 18.266729012017432
// mean absolute error: 9.602345117676546
// R Squared: 0.7747236216385236
// Explained Variance: 977.442442272066


// Gradient-Boosted Trees with maxIteration=10 - Only with "OriginVec", "DestVec", "DepDelayDouble", "TaxiOutDouble"
// sqrt(MSE): 17.80732797194016
// mean absolute error: 9.213998551801323
// R Squared: 0.7859123580993281
// Explained Variance: 974.7455190081098
