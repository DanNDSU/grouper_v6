package edu.internet2.middleware.grouper.app.teamsChannels;

import java.sql.Types;

import edu.internet2.middleware.grouper.ddl.DdlVersionBean;
import edu.internet2.middleware.grouper.ddl.GrouperDdlUtils;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Database;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Table;
import edu.internet2.middleware.grouperClient.jdbc.GcDbAccess;
import edu.internet2.middleware.grouperClient.util.GrouperClientUtils;

/**
 * Represents a Microsoft Teams channel membership (a conversationMember).
 *
 * Note: unlike an Azure group membership, a Teams channel membership has its
 * own opaque conversationMember id that is distinct from the user's id.  That
 * membership id (not the user id) is required in order to remove a member, so
 * this class tracks both.
 *
 * Modeled on GrouperAzureMembership.
 */
public class GrouperTeamsChannelMembership {

  /**
   * create the mock membership table for testing.
   * @param ddlVersionBean
   * @param database
   */
  public static void createTableTeamsChannelMembership(DdlVersionBean ddlVersionBean, Database database) {

    final String tableName = "mock_teams_channel_mship";

    try {
      new GcDbAccess().sql("select count(*) from " + tableName).select(int.class);
    } catch (Exception e) {

      Table loaderTable = GrouperDdlUtils.ddlutilsFindOrCreateTable(database, tableName);

      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "id", Types.VARCHAR, "256", true, true);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "channel_id", Types.VARCHAR, "128", false, true);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "user_id", Types.VARCHAR, "40", false, true);

      GrouperDdlUtils.ddlutilsFindOrCreateIndex(database, tableName, "mock_teams_mship_cid_idx", false, "channel_id");
      GrouperDdlUtils.ddlutilsFindOrCreateIndex(database, tableName, "mock_teams_mship_uid_idx", false, "user_id");
      GrouperDdlUtils.ddlutilsFindOrCreateIndex(database, tableName, "mock_teams_mship_cid_uid_idx", true, "channel_id", "user_id");

      GrouperDdlUtils.ddlutilsFindOrCreateForeignKey(database, tableName, "mock_teams_mship_cid_fkey", "mock_teams_channel", "channel_id", "id");
    }

  }

  @Override
  public String toString() {
    return GrouperClientUtils.toStringReflection(this);
  }

  /** the conversationMember id (opaque, required for delete) */
  private String id;

  /** the channel id this membership belongs to */
  private String channelId;

  /** the Entra user id of the member */
  private String userId;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getChannelId() {
    return channelId;
  }

  public void setChannelId(String channelId) {
    this.channelId = channelId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }
}
