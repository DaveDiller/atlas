package org.atlas.novice.apache;

import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.apache.commons.math3.geometry.euclidean.threed.Line;
import org.apache.commons.math3.geometry.euclidean.threed.Plane;
import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.RotationConvention;

/**
 * Demonstrates basic use of Apache Commons Math 3D geometry classes.
 */
public class ThreeDTest {

  public static void main(String[] args) {

    // 3D points (represented as Vector3D)
    Vector3D p1 = new Vector3D(1.0, 2.0, 3.0);
    Vector3D p2 = new Vector3D(4.0, 5.0, 6.0);
    System.out.println("=== 3D Points ===");
    System.out.println("p1: " + p1);
    System.out.println("p2: " + p2);

    // Vector arithmetic
    System.out.println("\n=== Vector Arithmetic ===");
    System.out.println("p1 + p2:     " + p1.add(p2));
    System.out.println("p2 - p1:     " + p2.subtract(p1));
    System.out.println("p1 * 2:      " + p1.scalarMultiply(2.0));
    System.out.println("p1 norm:     " + p1.getNorm());
    System.out.println("p1 normalized: " + p1.normalize());

    // Dot and cross products
    System.out.println("\n=== Dot and Cross Products ===");
    System.out.println("p1 . p2:  " + Vector3D.dotProduct(p1, p2));
    System.out.println("p1 x p2:  " + Vector3D.crossProduct(p1, p2));
    System.out.println("Distance:  " + Vector3D.distance(p1, p2));
    System.out.println("Angle (rad): " + Vector3D.angle(p1, p2));

    // Line through two points
    System.out.println("\n=== Line ===");
    Line line = new Line(p1, p2, 1.0e-10);
    System.out.println("Direction: " + line.getDirection());
    Vector3D p3 = new Vector3D(0.0, 0.0, 0.0);
    System.out.println("Distance from origin to line: " + line.distance(p3));

    // Plane through origin with normal along Z
    System.out.println("\n=== Plane ===");
    Plane xyPlane = new Plane(Vector3D.ZERO, Vector3D.PLUS_K, 1.0e-10);
    System.out.println("Normal: " + xyPlane.getNormal());
    System.out.println("Offset of p1 from XY plane: " + xyPlane.getOffset(p1));

    // Rotation: 90 degrees around the Z axis
    System.out.println("\n=== Rotation ===");
    Rotation rot = new Rotation(Vector3D.PLUS_K, Math.PI / 2.0,
                                RotationConvention.VECTOR_OPERATOR);
    Vector3D rotated = rot.applyTo(Vector3D.PLUS_I);
    System.out.println("X-axis rotated 90 deg around Z: " + rotated);
    System.out.println("Rotation angle (rad): " + rot.getAngle());
    System.out.println("Rotation axis: " + rot.getAxis(RotationConvention.VECTOR_OPERATOR));
  }
}
