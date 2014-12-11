package water;

import org.junit.BeforeClass;
import org.junit.Test;
import water.api.RequestServer;

import java.util.Properties;

import static org.junit.Assert.assertFalse;

public class AAA_PreCloudLock extends TestUtil {
  static boolean testRan = false;
  static final int CLOUD_SIZE = 5;
  static final int PARTIAL_CLOUD_SIZE = 2;

  @BeforeClass() public static void setup() { stall_till_cloudsize(CLOUD_SIZE); }

  private static void stall() {
    stall_till_cloudsize(PARTIAL_CLOUD_SIZE);
    // Start Nano server; block for starting
    Runnable run = H2O.finalizeRegistration();
    if( run != null ) 
      synchronized(run) {
        while( water.api.RequestServer.SERVER==null ) 
          try { run.wait(); }
          catch( InterruptedException ignore ) {}
      }
  }

  // ---
  // Should be able to load basic status pages without locking the cloud.
  @Test public void testBasicStatusPages() {
    // Serve some pages and confirm cloud does not lock
    try {
      TypeMap._check_no_locking=true; // Blow a nice assert if locking

      assertFalse(testRan);
      assertFalse(Paxos._cloudLocked);
      stall();
      assertFalse(Paxos._cloudLocked);

      serve("/",null);
      serve("/Cloud.json",null);
      serve("/junk",null);
      serve("/HTTP404",null);
      Properties parms = new Properties();
      parms.setProperty("src","./smalldata/iris");
      serve("/Typeahead/files",parms);
    } finally {
      TypeMap._check_no_locking=false;
      testRan = true;
    }
  }

  private void serve(String s, Properties parms) {
    RequestServer.SERVER.serve(s,"GET",null,parms==null?new Properties():parms);
    assertFalse("Check of pre-cloud classes failed.  You likely added a class to TypeMap.BOOTSTRAP_CLASSES[].  Page: " + s, Paxos._cloudLocked);
  }
}
