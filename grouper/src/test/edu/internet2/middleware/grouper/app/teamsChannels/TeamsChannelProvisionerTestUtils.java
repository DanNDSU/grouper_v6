package edu.internet2.middleware.grouper.app.teamsChannels;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import edu.internet2.middleware.grouper.app.azure.GrouperAzureUser;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningType;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningConsumer;
import edu.internet2.middleware.grouper.cfg.GrouperConfig;
import edu.internet2.middleware.grouper.cfg.dbConfig.GrouperDbConfig;
import edu.internet2.middleware.grouper.changeLog.esb.consumer.EsbConsumer;
import edu.internet2.middleware.grouper.hibernate.HibernateSession;
import edu.internet2.middleware.grouper.internal.util.GrouperUuid;
import edu.internet2.middleware.grouperClient.config.ConfigPropertiesCascadeBase;

/**
 * Builds a Teams channel provisioner configuration for tests, and seeds the mock
 * Entra directory that channel memberships resolve against.
 *
 * Modeled on AzureProvisionerTestUtils.
 */
public class TeamsChannelProvisionerTestUtils {

  /**
   * point the Graph external system at the mock service (or at a real tenant).
   *
   * The Teams channel provisioner reads grouper.azureConnector.&lt;id&gt;.* because it
   * shares the Azure app registration, so this configures the same properties the
   * Azure provisioner uses.  The login endpoint is served by the Teams mock, which
   * delegates the token call straight to the Azure mock.
   *
   * @param teamsExternalSystemConfigId
   * @param realTeams
   */
  public static void setupTeamsExternalSystem(String teamsExternalSystemConfigId, boolean realTeams) {

    String prefix = "grouper.azureConnector." + teamsExternalSystemConfigId + ".";

    if (realTeams) {

      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "graphEndpoint").value("https://graph.microsoft.com").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "graphVersion").value("v1.0").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "loginEndpoint").value("https://login.microsoftonline.com/").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "resource").value("https://graph.microsoft.com").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "resourceEndpoint").value("https://graph.microsoft.com/v1.0/").store();

    } else {

      int port = GrouperConfig.retrieveConfig().propertyValueInt("junit.test.tomcat.port", 8080);
      boolean ssl = GrouperConfig.retrieveConfig().propertyValueBoolean("junit.test.tomcat.ssl", false);
      String domainName = GrouperConfig.retrieveConfig().propertyValueString("junit.test.tomcat.domainName", "localhost");

      String baseUrl = "http" + (ssl ? "s" : "") + "://" + domainName + ":" + port + "/grouper/mockServices/teamsChannel/";

      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "clientId").value("myClient").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "clientSecret").value("pass").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "graphEndpoint").value("https://graph.microsoft.com").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "graphVersion").value("v1.0").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "loginEndpoint").value(baseUrl + "auth/").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "resource").value("https://graph.microsoft.com").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "resourceEndpoint").value(baseUrl).store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "tenantId").value("myTenant").store();
    }
  }

  /**
   * set a provisioner property unless the test overrode it via addExtraConfig
   */
  private static void configureProvisionerSuffix(TeamsChannelProvisionerTestConfigInput input, String suffix, String value) {
    if (!input.getExtraConfig().containsKey(suffix)) {
      new GrouperDbConfig().configFileName("grouper-loader.properties")
          .propertyName("provisioner." + input.getConfigId() + "." + suffix).value(value).store();
    }
  }

  /**
   * configure a Teams channel provisioner, e.g.
   *
   * TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
   *     new TeamsChannelProvisionerTestConfigInput()
   *       .assignMembershipType("private")
   *       .addExtraConfig("selectAllGroups", "false"));
   *
   * @param input
   */
  public static void configureTeamsChannelProvisioner(TeamsChannelProvisionerTestConfigInput input) {

    setupTeamsExternalSystem(input.getTeamsExternalSystemConfigId(), input.isRealTeams());

    if (4 != input.getGroupAttributeCount() && 5 != input.getGroupAttributeCount()) {
      throw new RuntimeException("Expecting 4 or 5 for groupAttributeCount but was '"
          + input.getGroupAttributeCount() + "'");
    }

    configureProvisionerSuffix(input, "class", GrouperTeamsChannelProvisioner.class.getName());
    configureProvisionerSuffix(input, "teamsExternalSystemConfigId", input.getTeamsExternalSystemConfigId());

    configureProvisionerSuffix(input, "debugLog", "true");
    configureProvisionerSuffix(input, "logAllObjectsVerbose", "true");
    configureProvisionerSuffix(input, "logCommandsAlways", "false");
    configureProvisionerSuffix(input, "showAdvanced", "true");
    configureProvisionerSuffix(input, "subjectSourcesToProvision", "jdbc");

    // the mock service is synchronous, so there is nothing to wait for after an insert
    configureProvisionerSuffix(input, "sleepBeforeSelectAfterInsertMillis", "0");

    configureProvisionerSuffix(input, "provisioningType", "membershipObjects");
    configureProvisionerSuffix(input, "operateOnGrouperGroups", "true");
    configureProvisionerSuffix(input, "operateOnGrouperEntities", "true");
    configureProvisionerSuffix(input, "operateOnGrouperMemberships", "true");

    configureProvisionerSuffix(input, "errorHandlingTargetObjectDoesNotExistIsAnError", "false");
    configureProvisionerSuffix(input, "errorHandlingShow", "true");

    // ---------------------------------------------------------------
    // channels (groups)
    // ---------------------------------------------------------------

    configureProvisionerSuffix(input, "customizeGroupCrud", "true");
    configureProvisionerSuffix(input, "insertGroups", "true");
    configureProvisionerSuffix(input, "updateGroups", "true");
    configureProvisionerSuffix(input, "deleteGroups", "true");
    configureProvisionerSuffix(input, "deleteGroupsIfNotExistInGrouper", "true");
    configureProvisionerSuffix(input, "selectGroups", "true");
    configureProvisionerSuffix(input, "selectAllGroups", input.isSelectAll() ? "true" : "false");

    int groupAttributeIndex = 0;

    // id - the channel thread id, read only: assigned by Teams, never written
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".name", "id");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".showAdvancedAttribute", "true");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".showAttributeCrud", "true");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".insert", "false");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".update", "false");
    groupAttributeIndex++;

    // displayName
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".name", "displayName");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".translateExpressionType", "grouperProvisioningGroupField");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".translateFromGrouperProvisioningGroupField", input.getDisplayNameMapping());
    groupAttributeIndex++;

    // description
    if (input.getGroupAttributeCount() >= 5) {
      configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".name", "description");
      configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".translateExpressionType", "grouperProvisioningGroupField");
      configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".translateFromGrouperProvisioningGroupField", "description");
      groupAttributeIndex++;
    }

    // teamId - either a fixed value or supplied per group/folder as metadata.
    // When it comes from metadata, leave the attribute untranslated so
    // TeamsChannelProvisioningTranslator can copy md_grouper_teamId onto it.
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".name", "teamId");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".showAdvancedAttribute", "true");
    if (input.isTeamIdFromMetadata()) {
      configureProvisionerSuffix(input, "teamIdMetadata", "true");
    } else {
      configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".translateExpressionType", "translationScript");
      configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".translateExpression", "${'" + input.getTeamId() + "'}");
    }
    groupAttributeIndex++;

    // membershipType - immutable after create
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".name", "membershipType");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".showAdvancedAttribute", "true");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".showAttributeCrud", "true");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".update", "false");
    if (input.isMembershipTypeFromMetadata()) {
      configureProvisionerSuffix(input, "membershipTypeMetadata", "true");
    } else {
      configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".translateExpressionType", "translationScript");
      configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".translateExpression", "${'" + input.getMembershipType() + "'}");
    }
    groupAttributeIndex++;

    configureProvisionerSuffix(input, "numberOfGroupAttributes", "" + groupAttributeIndex);

    // match a channel on displayName first (that is all we can search on before it
    // has an id), then on id once it is known
    configureProvisionerSuffix(input, "hasTargetGroupLink", "true");
    configureProvisionerSuffix(input, "groupMatchingAttributeCount", "2");
    configureProvisionerSuffix(input, "groupMatchingAttribute0name", "displayName");
    configureProvisionerSuffix(input, "groupMatchingAttribute1name", "id");

    configureProvisionerSuffix(input, "groupAttributeValueCacheHas", "true");
    configureProvisionerSuffix(input, "groupAttributeValueCache0has", "true");
    configureProvisionerSuffix(input, "groupAttributeValueCache0source", "target");
    configureProvisionerSuffix(input, "groupAttributeValueCache0type", "groupAttribute");
    configureProvisionerSuffix(input, "groupAttributeValueCache0groupAttribute", "id");
    configureProvisionerSuffix(input, "groupAttributeValueCache1has", "true");
    configureProvisionerSuffix(input, "groupAttributeValueCache1source", "target");
    configureProvisionerSuffix(input, "groupAttributeValueCache1type", "groupAttribute");
    configureProvisionerSuffix(input, "groupAttributeValueCache1groupAttribute", "displayName");
    // cache the parent teamId on gc_grouper_sync_group.  Channel operations are all
    // team-scoped, so the teamId has to survive the Grouper group going away - once
    // it does there is no grouper-side group left to translate it from.
    configureProvisionerSuffix(input, "groupAttributeValueCache2has", "true");
    configureProvisionerSuffix(input, "groupAttributeValueCache2source", "target");
    configureProvisionerSuffix(input, "groupAttributeValueCache2type", "groupAttribute");
    configureProvisionerSuffix(input, "groupAttributeValueCache2groupAttribute", "teamId");

    // ---------------------------------------------------------------
    // entities (Entra users) - resolved, never managed
    // ---------------------------------------------------------------

    configureProvisionerSuffix(input, "makeChangesToEntities", "false");
    configureProvisionerSuffix(input, "insertEntities", "false");
    configureProvisionerSuffix(input, "updateEntities", "false");
    configureProvisionerSuffix(input, "deleteEntities", "false");
    configureProvisionerSuffix(input, "selectEntities", "true");
    configureProvisionerSuffix(input, "selectAllEntities", input.isSelectAll() ? "true" : "false");

    // the Entra object id - this is what a channel membership is keyed by
    configureProvisionerSuffix(input, "targetEntityAttribute.0.name", "id");
    configureProvisionerSuffix(input, "targetEntityAttribute.0.showAdvancedAttribute", "true");
    configureProvisionerSuffix(input, "targetEntityAttribute.0.showAttributeCrud", "true");
    configureProvisionerSuffix(input, "targetEntityAttribute.0.insert", "false");
    configureProvisionerSuffix(input, "targetEntityAttribute.0.update", "false");

    // the value we can actually match a Grouper subject on
    configureProvisionerSuffix(input, "targetEntityAttribute.1.name", "userPrincipalName");
    configureProvisionerSuffix(input, "targetEntityAttribute.1.translateExpressionType", "grouperProvisioningEntityField");
    configureProvisionerSuffix(input, "targetEntityAttribute.1.translateFromGrouperProvisioningEntityField", "subjectId");

    configureProvisionerSuffix(input, "numberOfEntityAttributes", "2");

    configureProvisionerSuffix(input, "hasTargetEntityLink", "true");
    configureProvisionerSuffix(input, "entityMatchingAttributeCount", "1");
    configureProvisionerSuffix(input, "entityMatchingAttribute0name", "userPrincipalName");

    configureProvisionerSuffix(input, "entityAttributeValueCacheHas", "true");
    configureProvisionerSuffix(input, "entityAttributeValueCache0has", "true");
    configureProvisionerSuffix(input, "entityAttributeValueCache0source", "target");
    configureProvisionerSuffix(input, "entityAttributeValueCache0type", "entityAttribute");
    configureProvisionerSuffix(input, "entityAttributeValueCache0entityAttribute", "id");

    // ---------------------------------------------------------------
    // memberships
    // ---------------------------------------------------------------

    configureProvisionerSuffix(input, "customizeMembershipCrud", "true");
    configureProvisionerSuffix(input, "insertMemberships", "true");
    configureProvisionerSuffix(input, "deleteMemberships", "true");
    configureProvisionerSuffix(input, "deleteMembershipsIfNotExistInGrouper", "true");
    configureProvisionerSuffix(input, "selectMemberships", "true");

    // ---------------------------------------------------------------
    // diagnostics
    // ---------------------------------------------------------------

    configureProvisionerSuffix(input, "showProvisioningDiagnostics", "true");
    configureProvisionerSuffix(input, "selectAllGroupsDuringDiagnostics", "true");
    configureProvisionerSuffix(input, "selectAllEntitiesDuringDiagnostics", "true");
    configureProvisionerSuffix(input, "selectAllMembershipsDuringDiagnostics", "true");

    // ---------------------------------------------------------------
    // anything the test explicitly overrode
    // ---------------------------------------------------------------

    for (String key : input.getExtraConfig().keySet()) {
      String theValue = input.getExtraConfig().get(key);
      if (!StringUtils.isBlank(theValue)) {
        new GrouperDbConfig().configFileName("grouper-loader.properties")
            .propertyName("provisioner." + input.getConfigId() + "." + key).value(theValue).store();
      }
    }

    registerJobs(input.getConfigId());

    ConfigPropertiesCascadeBase.clearCache();
  }

  /**
   * register the full sync job and the incremental change log consumer
   * @param configId
   */
  public static void registerJobs(String configId) {

    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName("otherJob.provisioner_full_" + configId + ".class").value("edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningFullSyncJob").store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName("otherJob.provisioner_full_" + configId + ".quartzCron").value("9 59 23 31 12 ? 2099").store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName("otherJob.provisioner_full_" + configId + ".provisionerConfigId").value(configId).store();

    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName("changeLog.consumer.provisioner_incremental_" + configId + ".class").value(EsbConsumer.class.getName()).store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName("changeLog.consumer.provisioner_incremental_" + configId + ".provisionerConfigId").value(configId).store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName("changeLog.consumer.provisioner_incremental_" + configId + ".provisionerJobSyncType").value(GrouperProvisioningType.incrementalProvisionChangeLog.name()).store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName("changeLog.consumer.provisioner_incremental_" + configId + ".publisher.class").value(ProvisioningConsumer.class.getName()).store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName("changeLog.consumer.provisioner_incremental_" + configId + ".publisher.debug").value("true").store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName("changeLog.consumer.provisioner_incremental_" + configId + ".quartzCron").value("9 59 23 31 12 ? 2099").store();
  }

  /**
   * Seed the mock Entra directory.  The Teams channel provisioner never creates
   * users, so any subject that is going to end up in a channel has to already
   * exist in the directory - exactly as it would in a real tenant.
   *
   * The userPrincipalName is set to the subject id, matching the
   * targetEntityAttribute.1 translation configured above.
   *
   * @param subjectIds
   * @return map of subject id to the generated Entra object id
   */
  public static Map<String, String> createEntraUsers(String... subjectIds) {

    Map<String, String> subjectIdToEntraId = new LinkedHashMap<String, String>();

    for (String subjectId : subjectIds) {

      GrouperAzureUser grouperAzureUser = new GrouperAzureUser();
      grouperAzureUser.setId(GrouperUuid.getUuid());
      grouperAzureUser.setUserPrincipalName(subjectId);
      grouperAzureUser.setDisplayName("my name is " + subjectId);
      grouperAzureUser.setMailNickname(subjectId);
      grouperAzureUser.setAccountEnabled(true);

      HibernateSession.byObjectStatic().save(grouperAzureUser);

      subjectIdToEntraId.put(subjectId, grouperAzureUser.getId());
    }

    return subjectIdToEntraId;
  }

}