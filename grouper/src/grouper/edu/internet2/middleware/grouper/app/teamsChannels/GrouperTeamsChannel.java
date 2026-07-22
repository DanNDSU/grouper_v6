package edu.internet2.middleware.grouper.app.teamsChannels;

import java.sql.Types;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.internet2.middleware.grouper.app.provisioning.ProvisioningAttribute;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningGroup;
import edu.internet2.middleware.grouper.ddl.DdlVersionBean;
import edu.internet2.middleware.grouper.ddl.GrouperDdlUtils;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Database;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Table;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.jdbc.GcDbAccess;
import edu.internet2.middleware.grouperClient.util.GrouperClientUtils;

/**
 * Represents a Microsoft Teams channel.  This is the "group" object that
 * Grouper provisions.  A channel is always scoped to a parent team, so the
 * business key for a channel is the pair (teamId, id) where teamId is the
 * id of the M365 group/team that owns the channel and id is the channel's
 * own thread id (e.g. 19:....@thread.tacv2).
 *
 * Modeled on GrouperAzureGroup.
 */
public class GrouperTeamsChannel {

  /** default membership type when none is specified.  Note that only
   * private and shared channels support independent membership management. */
  public static final String defaultMembershipType = "standard";

  public static void main(String[] args) {

    GrouperTeamsChannel grouperTeamsChannel = new GrouperTeamsChannel();

    grouperTeamsChannel.setTeamId("57fb72d0-d811-46f4-8947-305e6072eaa5");
    grouperTeamsChannel.setId("19:4b6bed8d24574f6a9e436813cb2617d8@thread.tacv2");
    grouperTeamsChannel.setDisplayName("Architecture Discussion");
    grouperTeamsChannel.setDescription("desc");
    grouperTeamsChannel.setMembershipType("private");

    String json = GrouperUtil.jsonJacksonToString(grouperTeamsChannel.toJson(null, true));
    System.out.println(json);

    grouperTeamsChannel = GrouperTeamsChannel.fromJson(GrouperUtil.jsonJacksonNode(json));

    System.out.println(grouperTeamsChannel.toString());

  }

  /**
   * create the mock channel table for testing.
   * @param ddlVersionBean
   * @param database
   */
  public static void createTableTeamsChannel(DdlVersionBean ddlVersionBean, Database database) {

    final String tableName = "mock_teams_channel";

    try {
      new GcDbAccess().sql("select count(*) from " + tableName).select(int.class);
    } catch (Exception e) {

      Table loaderTable = GrouperDdlUtils.ddlutilsFindOrCreateTable(database, tableName);

      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "id", Types.VARCHAR, "128", true, true);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "team_id", Types.VARCHAR, "40", false, true);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "display_name", Types.VARCHAR, "256", false, true);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "description", Types.VARCHAR, "1024", false, false);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "membership_type", Types.VARCHAR, "32", false, true);

      GrouperDdlUtils.ddlutilsFindOrCreateIndex(database, tableName, "mock_teams_channel_disp_idx", false, "display_name");
      GrouperDdlUtils.ddlutilsFindOrCreateIndex(database, tableName, "mock_teams_channel_team_idx", false, "team_id");
    }

  }

  /**
   * translate this native channel into a Grouper target group.
   * @return the target group
   */
  public ProvisioningGroup toProvisioningGroup() {
    ProvisioningGroup targetGroup = new ProvisioningGroup(false);
    targetGroup.setId(this.id);
    targetGroup.setDisplayName(this.displayName);
    targetGroup.assignAttributeValue("teamId", this.teamId);
    targetGroup.assignAttributeValue("description", this.description);
    targetGroup.assignAttributeValue("displayName", this.displayName);
    targetGroup.assignAttributeValue("membershipType", this.membershipType);

    Map<String, Object> extensionAttributes = this.getExtensionAttributes();
    if (extensionAttributes != null) {
      for (String extensionAttributeName : GrouperUtil.nonNull(extensionAttributes.keySet())) {
        targetGroup.assignAttributeValue(extensionAttributeName, extensionAttributes.get(extensionAttributeName));
      }
    }

    return targetGroup;
  }

  /**
   * translate a Grouper target group into a native channel.
   * @param targetGroup
   * @param fieldNamesToSet if not null, only translate these fields
   * @return the channel
   */
  public static GrouperTeamsChannel fromProvisioningGroup(ProvisioningGroup targetGroup, Set<String> fieldNamesToSet) {

    GrouperTeamsChannel grouperTeamsChannel = new GrouperTeamsChannel();

    if (fieldNamesToSet == null || fieldNamesToSet.contains("id")) {
      grouperTeamsChannel.setId(targetGroup.getId());
    }
    if (fieldNamesToSet == null || fieldNamesToSet.contains("teamId")) {
      grouperTeamsChannel.setTeamId(targetGroup.retrieveAttributeValueString("teamId"));
    }
    if (fieldNamesToSet == null || fieldNamesToSet.contains("displayName")) {
      grouperTeamsChannel.setDisplayName(targetGroup.getDisplayName());
    }
    if (fieldNamesToSet == null || fieldNamesToSet.contains("description")) {
      grouperTeamsChannel.setDescription(targetGroup.retrieveAttributeValueString("description"));
    }
    if (fieldNamesToSet == null || fieldNamesToSet.contains("membershipType")) {
      grouperTeamsChannel.setMembershipType(targetGroup.retrieveAttributeValueString("membershipType"));
    }

    Map<String, ProvisioningAttribute> attributes = targetGroup.retrieveAttributes();
    for (String attributeName : GrouperUtil.nonNull(attributes).keySet()) {
      if (attributeName.startsWith("extension_")) {
        grouperTeamsChannel.getExtensionAttributes().put(attributeName, targetGroup.retrieveAttributeValue(attributeName));
      }
    }

    return grouperTeamsChannel;
  }

  @Override
  public String toString() {
    return GrouperClientUtils.toStringReflection(this);
  }

  /** the channel thread id, e.g. 19:...@thread.tacv2 (read-only from the target) */
  private String id;

  /** the id of the parent team (the M365 group id) */
  private String teamId;

  /** channel display name (max 50 chars in Teams) */
  private String displayName;

  /** optional channel description */
  private String description;

  /** standard, private, or shared.  Set at create time and cannot be changed. */
  private String membershipType = defaultMembershipType;

  private Map<String, Object> extensionAttributes = new HashMap<String, Object>();

  /** fields to select when reading channels from the Graph API */
  public static final String fieldsToSelect = "id,displayName,description,membershipType";

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getTeamId() {
    return teamId;
  }

  public void setTeamId(String teamId) {
    this.teamId = teamId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getMembershipType() {
    return membershipType;
  }

  public void setMembershipType(String membershipType) {
    this.membershipType = (StringUtils.isBlank(membershipType) ? defaultMembershipType : membershipType);
  }

  public Map<String, Object> getExtensionAttributes() {
    return extensionAttributes;
  }

  public void setExtensionAttributes(Map<String, Object> extensionAttributes) {
    this.extensionAttributes = extensionAttributes;
  }

  /**
   * convert from jackson json returned by the Graph API.
   * @param channelNode
   * @return the channel
   */
  public static GrouperTeamsChannel fromJson(JsonNode channelNode) {
    if (channelNode == null || !channelNode.has("id")) {
      return null;
    }
    GrouperTeamsChannel grouperTeamsChannel = new GrouperTeamsChannel();
    grouperTeamsChannel.id = GrouperUtil.jsonJacksonGetString(channelNode, "id");
    grouperTeamsChannel.displayName = GrouperUtil.jsonJacksonGetString(channelNode, "displayName");
    grouperTeamsChannel.description = GrouperUtil.jsonJacksonGetString(channelNode, "description");
    grouperTeamsChannel.setMembershipType(GrouperUtil.jsonJacksonGetString(channelNode, "membershipType", defaultMembershipType));

    Iterator<String> fieldNames = channelNode.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (StringUtils.startsWith(fieldName, "extension_") && !StringUtils.contains(fieldName, "@odata.type")) {
        JsonNode extensionNode = GrouperUtil.jsonJacksonGetNode(channelNode, fieldName);
        Object extensionValue = GrouperUtil.jsonConvertFrom(extensionNode, Object.class);
        grouperTeamsChannel.getExtensionAttributes().put(fieldName, extensionValue);
      }
    }

    return grouperTeamsChannel;
  }

  /**
   * convert to jackson json to send to the Graph API.
   * @param fieldNamesToSet if not null, only include these fields
   * @param isInsert if true this is a create (membershipType may be included)
   * @return the json object node
   */
  public ObjectNode toJson(Set<String> fieldNamesToSet, boolean isInsert) {
    ObjectNode result = GrouperUtil.jsonJacksonNode();

    if (fieldNamesToSet == null || fieldNamesToSet.contains("displayName")) {
      if (!StringUtils.isBlank(this.displayName)) {
        result.put("displayName", this.displayName);
      }
    }

    if (fieldNamesToSet == null || fieldNamesToSet.contains("description")) {
      result.put("description", this.description);
    }

    // membershipType can only be set at create time; it is immutable afterwards
    if (isInsert && (fieldNamesToSet == null || fieldNamesToSet.contains("membershipType"))) {
      if (!StringUtils.isBlank(this.membershipType)) {
        result.put("membershipType", this.membershipType);
      }
    }

    if (this.getExtensionAttributes() != null && this.getExtensionAttributes().size() > 0) {
      for (String attributeName : this.getExtensionAttributes().keySet()) {
        result.putPOJO(attributeName, this.getExtensionAttributes().get(attributeName));
      }
    }

    return result;
  }

  // ---- DB accessor helpers for the mock table (hibernate) ----

  public String getMembershipTypeDb() {
    return this.membershipType;
  }

  public void setMembershipTypeDb(String membershipType) {
    this.setMembershipType(membershipType);
  }

}
