///
/// org.javaseis.SeisException
///
///     Date       Author        Alterations
///----------------------------------------------------------------------------
///  1. xx-xx-2005 WALucas       Initial version.
///

package org.atlas.util;

import java.io.IOException;

public class SeisException extends IOException {

  private static final long serialVersionUID = 1L;


  /**
    * Constructs an instance of SeisException.
    */
   public SeisException() {
      super();
   }


   /**
    * Constructs an instance of SeisException.
    * @param message The exception message to set.
    * 
    * Please use the constructor below if there
    * is an underlying exception that is the cause 
    * for this SeisException.
    * 
    */
   public SeisException(String message) {
      super(message);
   }
   
   /**
    * Constructs a SeisException with a message and
    * and underlying cause.
    */
   public SeisException(String message, Throwable cause) {
     super(message, cause);
   }
}
