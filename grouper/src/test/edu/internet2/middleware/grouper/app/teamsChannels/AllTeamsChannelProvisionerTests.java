package edu.internet2.middleware.grouper.app.teamsChannels;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Modeled on AllAzureProvisionerTests.
 */
public class AllTeamsChannelProvisionerTests extends TestCase {

  public static Test suite() {
    TestSuite suite = new TestSuite(AllTeamsChannelProvisionerTests.class.getName());
    //$JUnit-BEGIN$
    suite.addTestSuite(GrouperTeamsChannelProvisionerTest.class);
    //$JUnit-END$
    return suite;
  }

}
