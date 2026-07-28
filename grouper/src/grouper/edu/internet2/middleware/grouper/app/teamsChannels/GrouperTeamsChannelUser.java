package edu.internet2.middleware.grouper.app.teamsChannels;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;

import edu.internet2.middleware.grouper.app.provisioning.ProvisioningEntity;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.util.GrouperClientUtils;

/**
 * Represents an Entra (Azure AD) user as seen by the Teams channel provisioner.
 *
 * This provisioner never creates, updates, or deletes users - it only needs to
 * RESOLVE them, because a Teams channel membership is keyed by the Entra user
 * id (a GUID) and Grouper subjects generally carry a netid / UPN instead.  This
 * class therefore only supports the read direction (fromJson /
 * toProvisioningEntity); there is deliberately no toJson or
 * fromProvisioningEntity write path.
 *
 * Modeled on GrouperAzureUser, pared down to the read-only fields.
 */
public class GrouperTeamsChannelUser {

  /** the $select list used on all user reads */
  public static final String fieldsToSelect = "id,displayName,userPrincipalName,mailNickname,onPremisesImmutableId,accountEnabled";

  /** the Entra object id (GUID) - this is what channel membership is keyed by */
  private String id;

  private String displayName;

  private String userPrincipalName;

  private String mailNickname;

  private String onPremisesImmutableId;

  private boolean accountEnabled = true;

  /**
   * convert to a provisioning entity.  The id is set as the entity id since
   * that is the value memberships are matched on.
   * @return the provisioning entity
   */
  public ProvisioningEntity toProvisioningEntity() {
    ProvisioningEntity targetEntity = new ProvisioningEntity(false);

    targetEntity.setId(this.id);
    targetEntity.assignAttributeValue("id", this.id);
    targetEntity.assignAttributeValue("displayName", this.displayName);
    targetEntity.assignAttributeValue("userPrincipalName", this.userPrincipalName);
    targetEntity.assignAttributeValue("mailNickname", this.mailNickname);
    targetEntity.assignAttributeValue("onPremisesImmutableId", this.onPremisesImmutableId);
    targetEntity.assignAttributeValue("accountEnabled", this.accountEnabled);

    return targetEntity;
  }

  /**
   * convert from jackson json
   * @param entityNode
   * @return the user, or null if the node has no id
   */
  public static GrouperTeamsChannelUser fromJson(JsonNode entityNode) {

    if (entityNode == null) {
      return null;
    }

    String id = GrouperUtil.jsonJacksonGetString(entityNode, "id");
    if (StringUtils.isBlank(id)) {
      return null;
    }

    GrouperTeamsChannelUser grouperTeamsChannelUser = new GrouperTeamsChannelUser();

    grouperTeamsChannelUser.id = id;
    grouperTeamsChannelUser.displayName = GrouperUtil.jsonJacksonGetString(entityNode, "displayName");
    grouperTeamsChannelUser.userPrincipalName = GrouperUtil.jsonJacksonGetString(entityNode, "userPrincipalName");
    grouperTeamsChannelUser.mailNickname = GrouperUtil.jsonJacksonGetString(entityNode, "mailNickname");
    grouperTeamsChannelUser.onPremisesImmutableId = GrouperUtil.jsonJacksonGetString(entityNode, "onPremisesImmutableId");
    grouperTeamsChannelUser.accountEnabled = GrouperUtil.jsonJacksonGetBoolean(entityNode, "accountEnabled", false);

    return grouperTeamsChannelUser;
  }

  @Override
  public String toString() {
    return GrouperClientUtils.toStringReflection(this);
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getUserPrincipalName() {
    return userPrincipalName;
  }

  public void setUserPrincipalName(String userPrincipalName) {
    this.userPrincipalName = userPrincipalName;
  }

  public String getMailNickname() {
    return mailNickname;
  }

  public void setMailNickname(String mailNickname) {
    this.mailNickname = mailNickname;
  }

  public String getOnPremisesImmutableId() {
    return onPremisesImmutableId;
  }

  public void setOnPremisesImmutableId(String onPremisesImmutableId) {
    this.onPremisesImmutableId = onPremisesImmutableId;
  }

  public boolean isAccountEnabled() {
    return accountEnabled;
  }

  public void setAccountEnabled(boolean accountEnabled) {
    this.accountEnabled = accountEnabled;
  }
}
