package org.atlas.novice.apache;

import org.apache.sis.coverage.grid.GridExtent;
import org.apache.sis.coverage.grid.GridGeometry;
import org.apache.sis.coverage.grid.GridOrientation;
import org.apache.sis.geometry.GeneralEnvelope;
import org.apache.sis.referencing.CommonCRS;
import org.opengis.metadata.spatial.DimensionNameType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import java.util.Arrays;

/**
 * Demonstrates basic use of Apache SIS GridGeometry for 3D grid specification.
 */
public class SasTest {

  public static void main(String[] args) {

    // Define a 3D grid extent: 500 x 400 x 100 cells
    GridExtent extent = new GridExtent(
        new DimensionNameType[] {
            DimensionNameType.COLUMN,    // X (e.g., inline)
            DimensionNameType.ROW,       // Y (e.g., crossline)
            DimensionNameType.VERTICAL   // Z (e.g., depth)
        },
        new long[] { 0,  0,  0 },        // low (inclusive)
        new long[] { 499, 399, 99 },     // high (inclusive)
        true                             // high values are inclusive
    );

    System.out.println("=== GridExtent ===");
    System.out.println("Dimensions: " + extent.getDimension());
    for (int d = 0; d < extent.getDimension(); d++) {
      System.out.printf("  Axis %d (%s): low=%d  high=%d  size=%d%n",
          d,
          extent.getAxisType(d).orElse(null),
          extent.getLow(d),
          extent.getHigh(d),
          extent.getSize(d));
    }

    // Define a real-world 3D envelope (WGS84 geographic + ellipsoidal height)
    CoordinateReferenceSystem crs3D = CommonCRS.WGS84.geographic3D();
    GeneralEnvelope envelope = new GeneralEnvelope(crs3D);
    envelope.setRange(0, 30.0, 35.0);    // Latitude (degrees)
    envelope.setRange(1, -96.0, -91.0);  // Longitude (degrees)
    envelope.setRange(2, 0.0, 5000.0);   // Elevation (meters)

    System.out.println("\n=== Envelope ===");
    System.out.println("CRS: " + crs3D.getName());
    for (int d = 0; d < envelope.getDimension(); d++) {
      System.out.printf("  Axis %d: %.2f to %.2f%n",
          d, envelope.getMinimum(d), envelope.getMaximum(d));
    }

    // Create GridGeometry: SIS computes the grid-to-CRS transform
    GridGeometry grid = new GridGeometry(extent, envelope, GridOrientation.HOMOTHETY);

    System.out.println("\n=== GridGeometry ===");
    System.out.println("Dimensions: " + grid.getDimension());
    System.out.println("Extent: " + grid.getExtent());
    System.out.println("Envelope: " + grid.getEnvelope());
    System.out.println("Resolution: " + Arrays.toString(grid.getResolution(true)));

    // Check a point: does a grid cell fall within bounds?
    System.out.println("\n=== Containment ===");
    long[] inside  = {250, 200, 50};
    long[] outside = {600, 200, 50};
    System.out.println("Contains " + Arrays.toString(inside)  + ": " + extent.contains(inside));
    System.out.println("Contains " + Arrays.toString(outside) + ": " + extent.contains(outside));

    // Extract a 2D horizontal slice (drop the vertical axis)
    GridGeometry horizontal = grid.selectDimensions(0, 1);
    System.out.println("\n=== 2D Horizontal Slice ===");
    System.out.println("Dimensions: " + horizontal.getDimension());
    System.out.println("Extent: " + horizontal.getExtent());
    System.out.println("Resolution: " + Arrays.toString(horizontal.getResolution(true)));
  }
}
