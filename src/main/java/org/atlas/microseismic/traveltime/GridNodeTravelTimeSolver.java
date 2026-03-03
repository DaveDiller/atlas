
package org.atlas.microseismic.traveltime;

/**
 * Classes that implement this interface calculate travel times to nodes on a 2D or 3D grid, but require the source to fall on a grid node.
 * A good example of this would be an Eikonal travel time solver.
 */

public interface GridNodeTravelTimeSolver {

  /**
   * Returns a hashkey for a travel time solver.  This is used as an optimization to avoid recomputing travel times for a particular source location,
   * if it's already been done.
   * 
   * @return  the hashkey for the travel time solver.
   */
  public String getHashKey();


  // public FOO computeTravelTimes(


}
