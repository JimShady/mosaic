package com.databricks.labs.mosaic.expressions.raster

import com.databricks.labs.mosaic.core.geometry.api.GeometryAPI
import com.databricks.labs.mosaic.core.index.IndexSystem
import com.databricks.labs.mosaic.functions.MosaicContext
import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.functions.{collect_list, lit}
import org.scalatest.matchers.should.Matchers._

trait RST_DerivedBandBehaviors extends QueryTest {

    // noinspection MapGetGet
    def behaviors(indexSystem: IndexSystem, geometryAPI: GeometryAPI): Unit = {
        spark.sparkContext.setLogLevel("FATAL")
        val mc = MosaicContext.build(indexSystem, geometryAPI)
        mc.register()
        val sc = spark
        import mc.functions._
        import sc.implicits._

        val rastersInMemory = spark.read
            .format("gdal")
            .option("raster_storage", "in-memory")
            .option("pathGlobFilter", "*_B01.TIF")
            .load("src/test/resources/modis")

        val funcName = "multiply"

        // Example code from: https://gdal.org/drivers/raster/vrt.html#vrt-that-multiplies-the-values-of-the-source-file-by-a-factor-of-1-5
        val pyFuncCode =
            """
              |import numpy as np
              |def multiply(in_ar, out_ar, xoff, yoff, xsize, ysize, raster_xsize,raster_ysize, buf_radius, gt, **kwargs):
              |    factor = 1.5
              |    out_ar[:] = np.round_(np.clip(in_ar[0] * factor,0,255))
              |""".stripMargin

        val gridTiles = rastersInMemory.union(rastersInMemory)
            .withColumn("tiles", rst_tessellate($"tile", 2))
            .select("path", "tiles")
            .groupBy("path")
            .agg(
                rst_derivedband(collect_list($"tiles"), lit(pyFuncCode), lit(funcName)).as("tiles")
            )
            .select("tiles")

        rastersInMemory.union(rastersInMemory)
            .createOrReplaceTempView("source")

        // Do not indent the code in the SQL statement
        // It will be wrongly interpreted in python as broken
        noException should be thrownBy spark.sql(
            """
              |select rst_derivedband(
              |   collect_list(tiles),
              |"
              |import numpy as np
              |def multiply(in_ar, out_ar, xoff, yoff, xsize, ysize, raster_xsize,raster_ysize, buf_radius, gt, **kwargs):
              |   factor = 1.2
              |   out_ar[:] = np.round_(np.clip(in_ar[0] * factor,0,255))
              |",
              |   "multiply"
              |) as tiles
              |from (
              |  select path, rst_tessellate(tile, 2) as tiles
              |  from source
              |)
              |group by path
              |""".stripMargin).take(1)

        val result = gridTiles.collect()

        result.length should be(rastersInMemory.count())

    }

}
