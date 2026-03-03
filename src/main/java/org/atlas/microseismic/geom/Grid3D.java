
package org.atlas.microseismic.geom;

import edu.mines.jtk.dsp.Sampling;

/**
 * This class represents a regular 3D grid defined by origin, increment, and node count along each axis.
 * <p>
 * Note that z always represents TVDSS (true vertical depth subsea).
 */

public class Grid3D {

  private final Sampling _samplingX;
  private final Sampling _samplingY;
  private final Sampling _samplingZ;

  
  /**
   * Constructs a 3D grid.
   * @param  nodeCountX  number of nodes along x.
   * @param  incrementX  increment along x.
   * @param  originX  origin (first value) along x.
   * @param  nodeCountY  number of nodes along y.
   * @param  incrementY  increment along y.
   * @param  originY  origin (first value) along y.
   * @param  nodeCountZ  number of nodes along z.
   * @param  incrementZ  increment along z.
   * @param  originZ  origin (first value) along z.  This is typically the elevation of the datum (top of the grid), which would be
   *                  negative (above sea level) for surface microseismic data.
   */
  public Grid3D(int nodeCountX, double incrementX, double originX,
                int nodeCountY, double incrementY, double originY,
                int nodeCountZ, double incrementZ, double originZ) {

    if (nodeCountX < 1) throw new IllegalArgumentException("nodeCountX must be > 0");
    if (nodeCountY < 1) throw new IllegalArgumentException("nodeCountY must be > 0");
    if (nodeCountZ < 1) throw new IllegalArgumentException("nodeCountZ must be > 0");
    if (nodeCountX > 1 && incrementX <= 0.0) throw new IllegalArgumentException("incrementX must be > 0");
    if (nodeCountY > 1 && incrementY <= 0.0) throw new IllegalArgumentException("incrementY must be > 0");
    if (nodeCountZ > 1 && incrementZ <= 0.0) throw new IllegalArgumentException("incrementZ must be > 0");

    _samplingX = new Sampling(nodeCountX, incrementX, originX);
    _samplingY = new Sampling(nodeCountY, incrementY, originY);
    _samplingZ = new Sampling(nodeCountZ, incrementZ, originZ);
  }
  

  /**
   * Constructs a 3D grid from three existing Sampling objects.
   */
  public Grid3D(Sampling samplingX, Sampling samplingY, Sampling samplingZ) {

    if (samplingX == null) throw new IllegalArgumentException("samplingX must not be null");
    if (samplingY == null) throw new IllegalArgumentException("samplingY must not be null");
    if (samplingZ == null) throw new IllegalArgumentException("samplingZ must not be null");

    _samplingX = samplingX;
    _samplingY = samplingY;
    _samplingZ = samplingZ;
  }


  public Sampling getSamplingX() { return _samplingX; }
  public Sampling getSamplingY() { return _samplingY; }
  public Sampling getSamplingZ() { return _samplingZ; }

  public int getNodeCountX() { return _samplingX.getCount(); }
  public int getNodeCountY() { return _samplingY.getCount(); }
  public int getNodeCountZ() { return _samplingZ.getCount(); }

  public double getIncrementX() { return _samplingX.getDelta(); }
  public double getIncrementY() { return _samplingY.getDelta(); }
  public double getIncrementZ() { return _samplingZ.getDelta(); }

  public double getOriginX() { return _samplingX.getFirst(); }
  public double getOriginY() { return _samplingY.getFirst(); }
  public double getOriginZ() { return _samplingZ.getFirst(); }

  public double getLastX() { return _samplingX.getLast(); }
  public double getLastY() { return _samplingY.getLast(); }
  public double getLastZ() { return _samplingZ.getLast(); }

  /** Total number of nodes in the grid. */
  public long getTotalNodeCount() {
    return (long) _samplingX.getCount() * _samplingY.getCount() * _samplingZ.getCount();
  }

  /** Returns the x coordinate at grid index indexX. */
  public double getX(int indexX) { return _samplingX.getValue(indexX); }

  /** Returns the y coordinate at grid index indexY. */
  public double getY(int indexY) { return _samplingY.getValue(indexY); }

  /** Returns the z coordinate at grid index indexZ. */
  public double getZ(int indexZ) { return _samplingZ.getValue(indexZ); }

  /** True if the given (x,y,z) point falls within the grid bounds. */
  public boolean isInBounds(double x, double y, double z) {
    return _samplingX.isInBounds(x) && _samplingY.isInBounds(y) && _samplingZ.isInBounds(z);
  }


  /**
   * Allocates a new 3D grid of nodes that are associated with the grid.
   * The slowest axis is Y, the intermediate axis is X, and the fast axis is Z (each
   * 2D plane is a depth slice with a common Y.
   */
  public float[][][] getNodes() {

    try {
      return new float[_samplingY.getCount()][_samplingX.getCount()][_samplingZ.getCount()];
    } catch (OutOfMemoryError e) {
      e.printStackTrace();
      throw new RuntimeException("Unable to allocate grid for nX=" + _samplingX.getCount()
				 + " nY=" + _samplingY.getCount() + " nX=" + _samplingZ.getCount());
    }
  }
  

  @Override
  public String toString() {
    return String.format(
        "Grid3D[x: %d nodes, dx=%.4g, ox=%.4g | " +
               "y: %d nodes, dy=%.4g, oy=%.4g | " +
               "z: %d nodes, dz=%.4g, oz=%.4g]",
        getNodeCountX(), getIncrementX(), getOriginX(),
        getNodeCountY(), getIncrementY(), getOriginY(),
        getNodeCountZ(), getIncrementZ(), getOriginZ());
  }


  public static void main(String[] args) {
    
    Grid3D grid = new Grid3D(500, 25.0, 1000.0,
                              400, 12.5, 2000.0,
                              100,  4.0,    0.0);

    System.out.println(grid);
    System.out.println("Total nodes: " + grid.getTotalNodeCount());

    System.out.println("\nX range: " + grid.getOriginX() + " to " + grid.getLastX());
    System.out.println("Y range: " + grid.getOriginY() + " to " + grid.getLastY());
    System.out.println("Z range: " + grid.getOriginZ() + " to " + grid.getLastZ());

    int _indexX = 10, _indexY = 20, _indexZ = 30;
    System.out.printf("\nCoordinate at index (%d, %d, %d):%n", _indexX, _indexY, _indexZ);
    System.out.printf("  x=%.1f  y=%.1f  z=%.1f%n",
        grid.getX(_indexX), grid.getY(_indexY), grid.getZ(_indexZ));

    double _testX = 5000.0, _testY = 3000.0, _testZ = 200.0;
    System.out.printf("\nIn bounds (%.0f, %.0f, %.0f): %b%n",
        _testX, _testY, _testZ, grid.isInBounds(_testX, _testY, _testZ));

    _testX = 999.0;
    System.out.printf("In bounds (%.0f, %.0f, %.0f): %b%n",
        _testX, _testY, _testZ, grid.isInBounds(_testX, _testY, _testZ));
  }
}
