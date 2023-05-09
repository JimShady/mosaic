package com.databricks.labs.mosaic.expressions.raster

import com.databricks.labs.mosaic.core.Mosaic
import com.databricks.labs.mosaic.core.geometry.api.GeometryAPI
import com.databricks.labs.mosaic.core.index.{IndexSystem, IndexSystemFactory}
import com.databricks.labs.mosaic.core.raster.MosaicRaster
import com.databricks.labs.mosaic.core.raster.api.RasterAPI
import com.databricks.labs.mosaic.expressions.base.{GenericExpressionFactory, WithExpressionInfo}
import com.databricks.labs.mosaic.expressions.raster.base.{RasterGeneratorExpression, RasterGridExpression}
import com.databricks.labs.mosaic.functions.MosaicExpressionConfig
import org.apache.hive.common.util.Murmur3
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry.FunctionBuilder
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.expressions.{CollectionGenerator, Expression, NullIntolerant}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.gdal.osr

import java.nio.file.Files

/**
  * Returns a set of new rasters with the specified tile size (tileWidth x
  * tileHeight).
  */
case class RST_GridTiles(
    pathExpr: Expression,
    resolutionExpr: Expression,
    expressionConfig: MosaicExpressionConfig
) extends CollectionGenerator
      with RasterGridExpression
      with NullIntolerant
      with CodegenFallback {

    val uuid: String = java.util.UUID.randomUUID().toString.replace("-", "_")

    /** The index system to be used. */
    val indexSystem: IndexSystem = IndexSystemFactory.getIndexSystem(expressionConfig.getIndexSystem)
    val geometryAPI: GeometryAPI = GeometryAPI(expressionConfig.getGeometryAPI)

    /**
      * The raster API to be used. Enable the raster so that subclasses dont
      * need to worry about this.
      */
    protected val rasterAPI: RasterAPI = RasterAPI(expressionConfig.getRasterAPI)
    rasterAPI.enable()

    override def position: Boolean = false

    override def inline: Boolean = false

    /**
      * Generators expressions require an abstraction for element type. Always
      * needs to be wrapped in a StructType. The actually type is that of the
      * structs element.
      */
    override def elementSchema: StructType = StructType(Array(StructField("path", StringType)))

    /**
      * Returns a set of new rasters with the specified tile size (tileWidth x
      * tileHeight).
      */
    override def rasterGenerator(raster: MosaicRaster, resolution: Int): Seq[(Long, (Int, Int, Int, Int))] = {
        val bbox = raster.bbox(geometryAPI)
        val cells = Mosaic
            .mosaicFill(bbox, resolution, keepCoreGeom = false, indexSystem, geometryAPI)
            .map(_.indexAsLong(indexSystem))

        val cellBBoxes = cells.map(cell => indexSystem.indexToGeometry(cell, geometryAPI).envelope)

        val rasters = cellBBoxes.map(cellBBox => {
            val extent = (
              cellBBox.minMaxCoord("X", "MIN"),
              cellBBox.minMaxCoord("Y", "MIN"),
              cellBBox.minMaxCoord("X", "MAX"),
              cellBBox.minMaxCoord("Y", "MAX")
            )
            val cellRaster = raster.getRasterForExtend(cellBBox, geometryAPI)
            val cellID = cellRaster.cellID
            val pixelValues = cellRaster.getPixelValues
            createCellRaster(cellID, pixelValues)
        })

        val tmpDir = Files.createTempDirectory(s"mosaic_$uuid").toFile.getAbsolutePath
        val outPath = s"$tmpDir/raster_${rasterId.toString.replace("-", "_")}.tif"

        null
    }

    def createCellRaster(
        cellID: Long,
        pixelValues: Seq[(Long, Seq[Double])]
    )

    override def eval(input: InternalRow): TraversableOnce[InternalRow] = {
        val inPath = pathExpr.eval(input).asInstanceOf[UTF8String].toString
        val checkpointPath = expressionConfig.getRasterCheckpoint
        val resolution = resolutionExpr.eval(input).asInstanceOf[Int]

        val raster = rasterAPI.raster(inPath)
        val tiles = rasterGenerator(raster, resolution)

    }

    override def children: Seq[Expression] = Seq(pathExpr)

}

/** Expression info required for the expression registration for spark SQL. */
object RST_GridTiles extends WithExpressionInfo {

    override def name: String = "rst_gridtiles"

    override def usage: String =
        """
          |_FUNC_(expr1) - Returns a set of new rasters with the specified tile size (tileWidth x tileHeight).
          |""".stripMargin

    override def example: String =
        """
          |    Examples:
          |      > SELECT _FUNC_(a, b);
          |        /path/to/raster_tile_1.tif
          |        /path/to/raster_tile_2.tif
          |        /path/to/raster_tile_3.tif
          |        ...
          |  """.stripMargin

    override def builder(expressionConfig: MosaicExpressionConfig): FunctionBuilder = {
        GenericExpressionFactory.getBaseBuilder[RST_GridTiles](3, expressionConfig)
    }

}