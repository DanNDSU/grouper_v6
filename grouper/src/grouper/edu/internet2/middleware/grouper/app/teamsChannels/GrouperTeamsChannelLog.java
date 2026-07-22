package edu.internet2.middleware.grouper.app.teamsChannels;

import java.util.Map;

import org.apache.commons.logging.Log;

import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.util.GrouperClientUtils;

/**
 * Debug logger for Teams channel Graph API traffic.
 *
 * Modeled on GrouperAzureLog.
 */
public class GrouperTeamsChannelLog {

  /** logger */
  private static final Log LOG = edu.internet2.middleware.grouper.util.GrouperUtil.getLog(GrouperTeamsChannelLog.class);

  public static void teamsLog(String message) {
    LOG.debug(message);
  }

  public static void teamsLog(Map<String, Object> messageMap, Long startTimeNanos) {
    if (LOG.isDebugEnabled()) {
      if (messageMap != null && startTimeNanos != null) {
        messageMap.put("elapsedMillis", (System.nanoTime() - startTimeNanos) / 1000000);
      }
      LOG.debug(GrouperClientUtils.mapToString(messageMap));
    }
  }

}
