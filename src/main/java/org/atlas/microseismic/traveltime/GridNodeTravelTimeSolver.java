
package org.atlas.microseismic.traveltime;

import org.atlas.microseismic.geom.Grid3D;

/**
 * Classes that implement this interface calculate travel times to nodes on a 2D or 3D grid, but require the source to fall on a grid node.
 * A good example of this would be an Eikonal travel time solver.
 */

public interface GridNodeTravelTimeSolver {

  /**
   * Returns the 3D grid that is associated with the travel time solver.  Never returns null.
   */
  public Grid3D getGrid3D();
  

  /**
   * Returns a hashkey for a travel time solver.  This is used as an optimization to avoid recomputing travel times for a particular source location,
   * if it's already been computed.
   * 
   * @return  the hashkey for the travel time solver.
   */
  public String getHashKey();


  /**
   * Computes the travel time in seconds from a source grid node to each receiver grid node.
   *
   * @param  indexX  the x index of the source grid node.
   * @param  indexY  the y index of the source grid node.
   * @param  indexZ  the z index of the source grid node.
   * @param  outputTravelTimeSeconds  the output travel times in seconds, for each node in the associated 3D grid.
   */
  public boolean computeTravelTimeSeconds(int indexX, int indexY, int indexZ, float[][][] outputTravelTimeSeconds);

}
