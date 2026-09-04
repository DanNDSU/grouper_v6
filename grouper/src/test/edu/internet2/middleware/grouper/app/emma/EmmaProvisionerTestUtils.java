package edu.internet2.middleware.grouper.app.emma;

import org.apache.commons.lang3.StringUtils;

import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningType;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningConsumer;
import edu.internet2.middleware.grouper.cfg.GrouperConfig;
import edu.internet2.middleware.grouper.cfg.dbConfig.GrouperDbConfig;
import edu.internet2.middleware.grouper.changeLog.esb.consumer.EsbConsumer;
import edu.internet2.middleware.grouperClient.config.ConfigPropertiesCascadeBase;

/**
 * Builds an Emma provisioner configuration for tests and points the Emma external
 * system at the mock service (EmmaMockServiceHandler).
 *
 * Modeled on TeamsChannelProvisionerTestUtils, but the Emma external system is a
 * WsBearerToken basic-auth system (public API key as user, private API key as
 * password) rather than an Azure connector, and Emma manages its own members, so
 * entity CRUD is enabled and there is no directory to pre-seed.
 */
public class EmmaProvisionerTestUtils {

  /**
   * point the Emma external system at the mock service (or at real Emma).
   *
   * Emma authenticates with HTTP Basic auth via a WsBearerToken external system:
   * the endpoint (which in production already includes the account id) plus
   * basicAuthUser / basicAuthPassword.  The mock service validates those two
   * credentials against grouper.wsBearerToken.&lt;id&gt;.basicAuth* in
   * EmmaMockServiceHandler.checkAuthorization.
   *
   * @param emmaExternalSystemConfigId
   * @param realEmma
   */
  public static void setupEmmaExternalSystem(String emmaExternalSystemConfigId, boolean realEmma) {

    String prefix = "grouper.wsBearerToken." + emmaExternalSystemConfigId + ".";

    if (realEmma) {

      // supply endpoint (including the account id), basicAuthUser (public API key)
      // and basicAuthPassword (private API key) through the usual config for a
      // real run; nothing mock-specific is set here.
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "endpoint").value("https://api.e2ma.net/CHANGE_ME_ACCOUNT_ID").store();

    } else {

      int port = GrouperConfig.retrieveConfig().propertyValueInt("junit.test.tomcat.port", 8080);
      boolean ssl = GrouperConfig.retrieveConfig().propertyValueBoolean("junit.test.tomcat.ssl", false);
      String domainName = GrouperConfig.retrieveConfig().propertyValueString("junit.test.tomcat.domainName", "localhost");

      // the mock account id is just part of the path; the handler ignores it and
      // routes on the trailing groups/members segments
      String baseUrl = "http" + (ssl ? "s" : "") + "://" + domainName + ":" + port + "/grouper/mockServices/emma/123456";

      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "endpoint").value(baseUrl).store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "basicAuthUser").value("myPublicApiKey").store();
      new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "basicAuthPassword").value("myPrivateApiKey").store();
    }

    // EmmaMockServiceHandler.checkAuthorization reads this from grouper.properties
    // to know which external system's credentials to validate the request against
    new GrouperDbConfig().configFileName("grouper.properties")
        .propertyName("grouperTest.exampleEmma.mockExternalSystem.configId").value(emmaExternalSystemConfigId).store();
  }

  /**
   * set a provisioner property unless the test overrode it via addExtraConfig
   */
  private static void configureProvisionerSuffix(EmmaProvisionerTestConfigInput input, String suffix, String value) {
    if (!input.getExtraConfig().containsKey(suffix)) {
      new GrouperDbConfig().configFileName("grouper-loader.properties")
          .propertyName("provisioner." + input.getConfigId() + "." + suffix).value(value).store();
    }
  }

  /**
   * configure an Emma provisioner, e.g.
   *
   * EmmaProvisionerTestUtils.configureEmmaProvisioner(
   *     new EmmaProvisionerTestConfigInput()
   *       .addExtraConfig("selectAllGroups", "false"));
   *
   * @param input
   */
  public static void configureEmmaProvisioner(EmmaProvisionerTestConfigInput input) {

    setupEmmaExternalSystem(input.getEmmaExternalSystemConfigId(), input.isRealEmma());

    if (2 != input.getGroupAttributeCount()) {
      throw new RuntimeException("Emma groups only have id and name; expecting 2 for groupAttributeCount but was '"
          + input.getGroupAttributeCount() + "'");
    }

    configureProvisionerSuffix(input, "class", EmmaProvisioner.class.getName());
    configureProvisionerSuffix(input, "emmaExternalSystemConfigId", input.getEmmaExternalSystemConfigId());

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
    // groups (Emma member groups)
    // ---------------------------------------------------------------

    configureProvisionerSuffix(input, "customizeGroupCrud", "true");
    configureProvisionerSuffix(input, "insertGroups", "true");
    configureProvisionerSuffix(input, "updateGroups", "true");
    configureProvisionerSuffix(input, "deleteGroups", "true");
    configureProvisionerSuffix(input, "deleteGroupsIfNotExistInGrouper", "true");
    configureProvisionerSuffix(input, "selectGroups", "true");
    configureProvisionerSuffix(input, "selectAllGroups", input.isSelectAll() ? "true" : "false");

    int groupAttributeIndex = 0;

    // id - the member_group_id, read only: assigned by Emma, never written
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".name", "id");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".showAdvancedAttribute", "true");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".showAttributeCrud", "true");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".insert", "false");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".update", "false");
    groupAttributeIndex++;

    // name - the group_name
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".name", "name");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".translateExpressionType", "grouperProvisioningGroupField");
    configureProvisionerSuffix(input, "targetGroupAttribute." + groupAttributeIndex + ".translateFromGrouperProvisioningGroupField", input.getGroupNameMapping());
    groupAttributeIndex++;

    configureProvisionerSuffix(input, "numberOfGroupAttributes", "" + groupAttributeIndex);

    // match a group on name first (that is all we can search on before it has an
    // id), then on id once it is known
    configureProvisionerSuffix(input, "hasTargetGroupLink", "true");
    configureProvisionerSuffix(input, "groupMatchingAttributeCount", "2");
    configureProvisionerSuffix(input, "groupMatchingAttribute0name", "name");
    configureProvisionerSuffix(input, "groupMatchingAttribute1name", "id");

    configureProvisionerSuffix(input, "groupAttributeValueCacheHas", "true");
    configureProvisionerSuffix(input, "groupAttributeValueCache0has", "true");
    configureProvisionerSuffix(input, "groupAttributeValueCache0source", "target");
    configureProvisionerSuffix(input, "groupAttributeValueCache0type", "groupAttribute");
    configureProvisionerSuffix(input, "groupAttributeValueCache0groupAttribute", "id");
    configureProvisionerSuffix(input, "groupAttributeValueCache1has", "true");
    configureProvisionerSuffix(input, "groupAttributeValueCache1source", "target");
    configureProvisionerSuffix(input, "groupAttributeValueCache1type", "groupAttribute");
    configureProvisionerSuffix(input, "groupAttributeValueCache1groupAttribute", "name");

    // ---------------------------------------------------------------
    // entities (Emma members) - fully managed
    // ---------------------------------------------------------------

    configureProvisionerSuffix(input, "makeChangesToEntities", "true");
    configureProvisionerSuffix(input, "insertEntities", "true");
    configureProvisionerSuffix(input, "updateEntities", "true");
    configureProvisionerSuffix(input, "deleteEntities", "true");
    configureProvisionerSuffix(input, "selectEntities", "true");
    configureProvisionerSuffix(input, "selectAllEntities", input.isSelectAll() ? "true" : "false");
    configureProvisionerSuffix(input, "deleteEntitiesIfNotExistInGrouper", "false");

    int entityAttributeIndex = 0;

    // id - the member_id, read only: assigned by Emma, never written
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".name", "id");
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".showAdvancedAttribute", "true");
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".showAttributeCrud", "true");
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".insert", "false");
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".update", "false");
    entityAttributeIndex++;

    // email - Emma keys members on email, and it is what we match a subject on
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".name", "email");
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".translateExpressionType", "translationScript");
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".translateExpression",
        "${grouperProvisioningEntity.retrieveAttributeValueString('email')}");
    entityAttributeIndex++;

    // firstName -> Emma fields.first_name
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".name", "firstName");
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".translateExpressionType", "translationScript");
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".translateExpression",
        "${grouperProvisioningEntity.retrieveAttributeValueString('givenName')}");
    entityAttributeIndex++;

    // lastName -> Emma fields.last_name
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".name", "lastName");
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".translateExpressionType", "translationScript");
    configureProvisionerSuffix(input, "targetEntityAttribute." + entityAttributeIndex + ".translateExpression",
        "${grouperProvisioningEntity.retrieveAttributeValueString('sn')}");
    entityAttributeIndex++;

    // tests that add a user-defined field bump entityAttributeCount and register
    // the extra targetEntityAttribute.N.* via addExtraConfig
    configureProvisionerSuffix(input, "numberOfEntityAttributes", "" + input.getEntityAttributeCount());

    configureProvisionerSuffix(input, "hasTargetEntityLink", "true");
    configureProvisionerSuffix(input, "entityMatchingAttributeCount", "2");
    configureProvisionerSuffix(input, "entityMatchingAttribute0name", "email");
    configureProvisionerSuffix(input, "entityMatchingAttribute1name", "id");

    configureProvisionerSuffix(input, "entityAttributeValueCacheHas", "true");
    configureProvisionerSuffix(input, "entityAttributeValueCache0has", "true");
    configureProvisionerSuffix(input, "entityAttributeValueCache0source", "target");
    configureProvisionerSuffix(input, "entityAttributeValueCache0type", "entityAttribute");
    configureProvisionerSuffix(input, "entityAttributeValueCache0entityAttribute", "id");
    configureProvisionerSuffix(input, "entityAttributeValueCache1has", "true");
    configureProvisionerSuffix(input, "entityAttributeValueCache1source", "target");
    configureProvisionerSuffix(input, "entityAttributeValueCache1type", "entityAttribute");
    configureProvisionerSuffix(input, "entityAttributeValueCache1entityAttribute", "email");

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

}
