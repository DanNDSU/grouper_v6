package edu.internet2.middleware.grouper.app.teamsChannels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import edu.internet2.middleware.grouper.app.provisioning.ProvisionerStartWithBase;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningConfiguration;
import edu.internet2.middleware.grouper.util.GrouperUtil;

/**
 * "Start with" wizard configuration for the Teams channel provisioner.  Emits a
 * baseline provisioner configuration that operates on groups and memberships
 * (not entities), with the channel displayName/description/teamId/membershipType
 * attributes wired up.
 *
 * Modeled on AzureProvisioningStartWith.
 */
public class TeamsChannelProvisioningStartWith extends ProvisionerStartWithBase {

  @Override
  public String getPropertyValueThatIdentifiesThisConfig() {
    return "teamsChannelCommon";
  }

  @Override
  public void populateProvisionerConfigurationValuesFromStartWith(
      Map<String, String> startWithSuffixToValue,
      Map<String, Object> provisionerSuffixToValue) {

    provisionerSuffixToValue.put("teamsExternalSystemConfigId", startWithSuffixToValue.get("teamsExternalSystemConfigId"));

    // ---- groups (channels) ----
    {
      int numberOfGroupAttributes = 0;

      provisionerSuffixToValue.put("operateOnGrouperGroups", "true");

      // id (channel thread id) - read only, resolved from the target, not inserted/updated
      provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".name", "id");
      provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".showAdvancedAttribute", "true");
      provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".showAttributeCrud", "true");
      provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".insert", "false");
      provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".update", "false");
      numberOfGroupAttributes++;

      // displayName
      String groupDisplayNameAttributeType = startWithSuffixToValue.get("groupDisplayNameAttributeValue");
      if (StringUtils.equals("script", groupDisplayNameAttributeType)) {
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".translateExpressionType", "translationScript");
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".translateExpression", startWithSuffixToValue.get("groupDisplayNameTranslationScript"));
      } else if (StringUtils.equals("other", groupDisplayNameAttributeType)) {
        // do nothing
      } else {
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".translateExpressionType", "grouperProvisioningGroupField");
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".translateFromGrouperProvisioningGroupField", groupDisplayNameAttributeType);
      }
      provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".name", "displayName");
      numberOfGroupAttributes++;

      // description
      if (GrouperUtil.booleanValue(startWithSuffixToValue.get("useGroupDescription"), true)) {
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".name", "description");
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".translateExpressionType", "grouperProvisioningGroupField");
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".translateFromGrouperProvisioningGroupField", "description");
        numberOfGroupAttributes++;
      }

      // teamId - required; may be a fixed value or driven by metadata
      provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".name", "teamId");
      String teamIdValue = startWithSuffixToValue.get("teamIdValue");
      if (StringUtils.isNotBlank(teamIdValue)) {
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".translateExpressionType", "translationScript");
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".translateExpression", "${'" + teamIdValue + "'}");
      }
      numberOfGroupAttributes++;

      // membershipType
      provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".name", "membershipType");
      String membershipTypeValue = startWithSuffixToValue.get("membershipTypeValue");
      if (StringUtils.isNotBlank(membershipTypeValue)) {
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".translateExpressionType", "translationScript");
        provisionerSuffixToValue.put("targetGroupAttribute." + numberOfGroupAttributes + ".translateExpression", "${'" + membershipTypeValue + "'}");
      }
      numberOfGroupAttributes++;

      provisionerSuffixToValue.put("numberOfGroupAttributes", numberOfGroupAttributes);

      // group matching / caching: match on id, search on displayName
      provisionerSuffixToValue.put("groupAttributeValueCacheHas", "true");
      provisionerSuffixToValue.put("groupAttributeValueCache0has", "true");
      provisionerSuffixToValue.put("groupAttributeValueCache0source", "target");
      provisionerSuffixToValue.put("groupAttributeValueCache0type", "groupAttribute");
      provisionerSuffixToValue.put("groupAttributeValueCache0groupAttribute", "id");

      String groupSearchMatchingAttribute = GrouperUtil.defaultIfBlank(startWithSuffixToValue.get("groupSearchMatchingAttribute"), "displayName");
      provisionerSuffixToValue.put("groupAttributeValueCache1has", "true");
      provisionerSuffixToValue.put("groupAttributeValueCache1source", "target");
      provisionerSuffixToValue.put("groupAttributeValueCache1type", "groupAttribute");
      provisionerSuffixToValue.put("groupAttributeValueCache1groupAttribute", groupSearchMatchingAttribute);

      provisionerSuffixToValue.put("hasTargetGroupLink", "true");
      provisionerSuffixToValue.put("groupMatchingAttributeCount", "2");
      provisionerSuffixToValue.put("groupMatchingAttribute0name", groupSearchMatchingAttribute);
      provisionerSuffixToValue.put("groupMatchingAttribute1name", "id");
    }

    // ---- metadata flags ----
    if (GrouperUtil.booleanValue(startWithSuffixToValue.get("hasMetadataForTeamId"), false)) {
      provisionerSuffixToValue.put("teamIdMetadata", "true");
    }
    if (GrouperUtil.booleanValue(startWithSuffixToValue.get("hasMetadataForMembershipType"), false)) {
      provisionerSuffixToValue.put("membershipTypeMetadata", "true");
    }

    // ---- entities (users) : referenced, never managed ----
    {
      provisionerSuffixToValue.put("operateOnGrouperEntities", "true");
      provisionerSuffixToValue.put("hasTargetEntityLink", "true");
      // this provisioner never creates/updates/deletes users
      provisionerSuffixToValue.put("makeChangesToEntities", "false");

      // the entity target id is the Entra user id (object id)
      provisionerSuffixToValue.put("targetEntityAttribute.0.name", "id");

      String entitySearchMatchingAttribute = GrouperUtil.defaultIfBlank(startWithSuffixToValue.get("entitySearchMatchingAttribute"), "id");

      provisionerSuffixToValue.put("numberOfEntityAttributes", "1");
      provisionerSuffixToValue.put("entityAttributeValueCacheHas", "true");
      provisionerSuffixToValue.put("entityAttributeValueCache0has", "true");
      provisionerSuffixToValue.put("entityAttributeValueCache0source", "target");
      provisionerSuffixToValue.put("entityAttributeValueCache0type", "entityAttribute");
      provisionerSuffixToValue.put("entityAttributeValueCache0entityAttribute", "id");

      provisionerSuffixToValue.put("entityMatchingAttributeCount", "1");
      provisionerSuffixToValue.put("entityMatchingAttribute0name", entitySearchMatchingAttribute);
    }

    // ---- memberships ----
    provisionerSuffixToValue.put("operateOnGrouperMemberships", "true");
    provisionerSuffixToValue.put("provisioningType", "membershipObjects");

    provisionerSuffixToValue.put("class", GrouperTeamsChannelProvisioner.class.getName());

    if (GrouperUtil.booleanValue(startWithSuffixToValue.get("addDisabledFullSyncDaemon"), true)
        || GrouperUtil.booleanValue(startWithSuffixToValue.get("addDisabledIncrementalSyncDaemon"), true)) {
      provisionerSuffixToValue.put("showAdvanced", "true");
    }
  }

  @Override
  public Map<String, String> screenRedraw(Map<String, String> suffixToValue, Set<String> suffixesUserJustChanged) {
    return new HashMap<String, String>();
  }

  @Override
  public void validatePreSave(boolean isInsert, List<String> errorsToDisplay, Map<String, String> validationErrorsToDisplay) {
    super.validatePreSave(isInsert, errorsToDisplay, validationErrorsToDisplay);
  }

  @Override
  public Class<? extends ProvisioningConfiguration> getProvisioningConfiguration() {
    return TeamsChannelProvisionerConfiguration.class;
  }

}
