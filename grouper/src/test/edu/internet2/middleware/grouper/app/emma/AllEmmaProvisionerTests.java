package edu.internet2.middleware.grouper.app.emma;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Modeled on AllTeamsChannelProvisionerTests.
 */
public class AllEmmaProvisionerTests extends TestCase {

  public static Test suite() {
    TestSuite suite = new TestSuite(AllEmmaProvisionerTests.class.getName());
    //$JUnit-BEGIN$
    suite.addTestSuite(GrouperEmmaProvisionerTest.class);
    //$JUnit-END$
    return suite;
  }

}
