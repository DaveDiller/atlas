
package org.atlas.microseismic.traveltime;

import org.atlas.microseismic.geom.Grid3D;

/**
 * This class computes travel times using a constant velocity and straight-ray assumption.
 * Travel time is the Euclidean distance from the source node to each receiver node, divided by the velocity.
 */

public class ConstantVelTravelTimeSolver implements GridNodeTravelTimeSolver {

  private final Grid3D _grid3D;
  private final double _constantVelocity;


  /**
   * Constructs a constant-velocity travel time solver.
   *
   * @param  grid3D  the 3D grid defining the node geometry.
   * @param  constantVelocity  the constant velocity in feet or meters per second.
   */
  public ConstantVelTravelTimeSolver(Grid3D grid3D, double constantVelocity) {

    if (grid3D == null) throw new IllegalArgumentException("grid3D must not be null");
    if (constantVelocity <= 0.0) throw new IllegalArgumentException("constantVelocity must be > 0");

    _grid3D = grid3D;
    _constantVelocity = constantVelocity;
  }


  @Override
  public Grid3D getGrid3D() {
    return _grid3D;
  }


  @Override
  public String getHashKey() {
    return "ConstantVelTravelTimeSolver_v=" + _constantVelocity + "_" + _grid3D.toString();
  }


  @Override
  public boolean computeTravelTimeSeconds(int indexX, int indexY, int indexZ,
                                          float[][][] outputTravelTimeSeconds) {

    double sourceX = _grid3D.getX(indexX);
    double sourceY = _grid3D.getY(indexY);
    double sourceZ = _grid3D.getZ(indexZ);

    int nodeCountX = _grid3D.getNodeCountX();
    int nodeCountY = _grid3D.getNodeCountY();
    int nodeCountZ = _grid3D.getNodeCountZ();

    for (int iy = 0; iy < nodeCountY; iy++) {
      double dy = _grid3D.getY(iy) - sourceY;

      for (int ix = 0; ix < nodeCountX; ix++) {
        double dx = _grid3D.getX(ix) - sourceX;

        for (int iz = 0; iz < nodeCountZ; iz++) {
          double dz = _grid3D.getZ(iz) - sourceZ;
          double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
          outputTravelTimeSeconds[iy][ix][iz] = (float) (distance / _constantVelocity);
        }
      }
    }

    return true;
  }


  public static void main(String[] args) {

    Grid3D grid = new Grid3D(5, 100.0, 0.0,
                               4, 100.0, 0.0,
                               3,  50.0, 0.0);

    double velocity = 3000.0;
    ConstantVelTravelTimeSolver solver = new ConstantVelTravelTimeSolver(grid, velocity);

    System.out.println("Grid: " + grid);
    System.out.println("Velocity: " + velocity + " m/s");
    System.out.println("Hash key: " + solver.getHashKey());

    float[][][] travelTimes = grid.getNodes();

    int srcX = 0, srcY = 0, srcZ = 0;
    solver.computeTravelTimeSeconds(srcX, srcY, srcZ, travelTimes);

    System.out.printf("%nTravel times from source at index (%d, %d, %d):%n", srcX, srcY, srcZ);
    System.out.printf("  Source coordinates: (%.1f, %.1f, %.1f)%n",
                      grid.getX(srcX), grid.getY(srcY), grid.getZ(srcZ));

    System.out.println("\nSample travel times:");
    for (int iy = 0; iy < grid.getNodeCountY(); iy++) {
      for (int ix = 0; ix < grid.getNodeCountX(); ix++) {
        System.out.printf("  [iy=%d, ix=%d, iz=0]  coord=(%.0f, %.0f, %.0f)  time=%.6f s%n",
                          iy, ix, grid.getX(ix), grid.getY(iy), grid.getZ(0),
                          travelTimes[iy][ix][0]);
      }
    }
  }
}
