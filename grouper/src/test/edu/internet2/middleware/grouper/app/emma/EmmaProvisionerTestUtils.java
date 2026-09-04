package edu.internet2.middleware.grouper.app.emma;

import org.apache.commons.lang3.StringUtils;

import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningFullSyncJob;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningType;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningConsumer;
import edu.internet2.middleware.grouper.cfg.GrouperConfig;
import edu.internet2.middleware.grouper.cfg.dbConfig.GrouperDbConfig;
import edu.internet2.middleware.grouper.changeLog.esb.consumer.EsbConsumer;
import edu.internet2.middleware.grouperClient.config.ConfigPropertiesCascadeBase;

/**
 * Builds an Emma provisioner configuration for tests, and points the
 * WsBearerToken external system it uses at the mock service.
 *
 * Modeled on DuoProvisionerTestUtils / FreshRequesterProvisionerTestUtils, since
 * Emma authenticates the same way as Freshservice: HTTP Basic auth through a
 * WsBearerToken external system.
 */
public class EmmaProvisionerTestUtils {

  /**
   * point a WsBearerToken external system at the mock Emma service.
   *
   * Requires that grouper/conf/grouper.properties (local, not checked in) has:
   *   grouperExtraMockServer.emma.class = edu.internet2.middleware.grouper.app.emma.EmmaMockServiceHandler
   *   grouperExtraMockServer.emma.path  = emma
   * so MockServiceServlet routes /grouper/mockServices/emma to EmmaMockServiceHandler.
   *
   * @param emmaExternalSystemConfigId
   */
  public static void setupEmmaExternalSystem(String emmaExternalSystemConfigId) {

    int port = GrouperConfig.retrieveConfig().propertyValueInt("junit.test.tomcat.port", 8080);
    boolean ssl = GrouperConfig.retrieveConfig().propertyValueBoolean("junit.test.tomcat.ssl", false);
    String domainName = GrouperConfig.retrieveConfig().propertyValueString("junit.test.tomcat.domainName", "localhost");

    String baseUrl = "http" + (ssl ? "s" : "") + "://" + domainName + ":" + port + "/grouper/mockServices/emma";

    String prefix = "grouper.wsBearerToken." + emmaExternalSystemConfigId + ".";

    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "endpoint").value(baseUrl).store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "httpAuthnType").value("basicAuth").store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "basicAuthUser").value("emmaPublicKey").store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "basicAuthPassword").value("emmaPrivateKey").store();
    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName(prefix + "basicAuthStandardUserOrder").value("false").store();

    // EmmaMockServiceHandler.checkAuthorization() looks up the wsBearerToken configId through this indirection
    new GrouperDbConfig().configFileName("grouper.properties").propertyName("grouperTest.exampleEmma.mockExternalSystem.configId").value(emmaExternalSystemConfigId).store();

    ConfigPropertiesCascadeBase.clearCache();
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

    setupEmmaExternalSystem(input.getEmmaExternalSystemConfigId());

    configureProvisionerSuffix(input, "class", EmmaProvisioner.class.getName());
    configureProvisionerSuffix(input, "emmaExternalSystemConfigId", input.getEmmaExternalSystemConfigId());

    configureProvisionerSuffix(input, "debugLog", "true");
    configureProvisionerSuffix(input, "logAllObjectsVerbose", "true");
    configureProvisionerSuffix(input, "logCommandsAlways", "false");
    configureProvisionerSuffix(input, "showAdvanced", "true");
    configureProvisionerSuffix(input, "subjectSourcesToProvision", "jdbc");

    configureProvisionerSuffix(input, "provisioningType", "membershipObjects");
    configureProvisionerSuffix(input, "operateOnGrouperGroups", "true");
    configureProvisionerSuffix(input, "operateOnGrouperEntities", "true");
    configureProvisionerSuffix(input, "operateOnGrouperMemberships", "true");

    configureProvisionerSuffix(input, "errorHandlingTargetObjectDoesNotExistIsAnError", "false");
    configureProvisionerSuffix(input, "errorHandlingShow", "true");

    // ---------------------------------------------------------------
    // groups
    // ---------------------------------------------------------------

    configureProvisionerSuffix(input, "customizeGroupCrud", "true");
    configureProvisionerSuffix(input, "insertGroups", "true");
    configureProvisionerSuffix(input, "updateGroups", "true");
    configureProvisionerSuffix(input, "deleteGroups", "true");
    configureProvisionerSuffix(input, "deleteGroupsIfNotExistInGrouper", "true");
    configureProvisionerSuffix(input, "selectGroups", "true");
    configureProvisionerSuffix(input, "selectAllGroups", input.isSelectAll() ? "true" : "false");

    // id - assigned by Emma, read only
    configureProvisionerSuffix(input, "targetGroupAttribute.0.name", "id");
    configureProvisionerSuffix(input, "targetGroupAttribute.0.showAdvancedAttribute", "true");
    configureProvisionerSuffix(input, "targetGroupAttribute.0.showAttributeCrud", "true");
    configureProvisionerSuffix(input, "targetGroupAttribute.0.insert", "false");
    configureProvisionerSuffix(input, "targetGroupAttribute.0.update", "false");

    // name - Emma groups have no full-path concept, so use the group extension
    configureProvisionerSuffix(input, "targetGroupAttribute.1.name", "name");
    configureProvisionerSuffix(input, "targetGroupAttribute.1.translateExpressionType", "grouperProvisioningGroupField");
    configureProvisionerSuffix(input, "targetGroupAttribute.1.translateFromGrouperProvisioningGroupField", "extension");

    configureProvisionerSuffix(input, "numberOfGroupAttributes", "2");

    // match a group on name first (before it has a target id), then on id once known
    configureProvisionerSuffix(input, "hasTargetGroupLink", "true");
    configureProvisionerSuffix(input, "groupMatchingAttributeCount", "2");
    configureProvisionerSuffix(input, "groupMatchingAttribute0name", "name");
    configureProvisionerSuffix(input, "groupMatchingAttribute1name", "id");

    configureProvisionerSuffix(input, "groupAttributeValueCacheHas", "true");
    configureProvisionerSuffix(input, "groupAttributeValueCache0has", "true");
    configureProvisionerSuffix(input, "groupAttributeValueCache0source", "target");
    configureProvisionerSuffix(input, "groupAttributeValueCache0type", "groupAttribute");
    configureProvisionerSuffix(input, "groupAttributeValueCache0groupAttribute", "id");

    // ---------------------------------------------------------------
    // entities (members)
    // ---------------------------------------------------------------

    configureProvisionerSuffix(input, "customizeEntityCrud", "true");
    configureProvisionerSuffix(input, "makeChangesToEntities", "true");
    configureProvisionerSuffix(input, "insertEntities", "true");
    configureProvisionerSuffix(input, "updateEntities", "true");
    configureProvisionerSuffix(input, "deleteEntities", "true");
    configureProvisionerSuffix(input, "selectEntities", "true");
    configureProvisionerSuffix(input, "selectAllEntities", input.isSelectAll() ? "true" : "false");

    // id - assigned by Emma, read only
    configureProvisionerSuffix(input, "targetEntityAttribute.0.name", "id");
    configureProvisionerSuffix(input, "targetEntityAttribute.0.showAdvancedAttribute", "true");
    configureProvisionerSuffix(input, "targetEntityAttribute.0.showAttributeCrud", "true");
    configureProvisionerSuffix(input, "targetEntityAttribute.0.insert", "false");
    configureProvisionerSuffix(input, "targetEntityAttribute.0.update", "false");

    // email - the value Emma members are keyed by, and what we can match a Grouper subject on
    configureProvisionerSuffix(input, "targetEntityAttribute.1.name", "email");
    configureProvisionerSuffix(input, "targetEntityAttribute.1.translateExpressionType", "grouperProvisioningEntityField");
    configureProvisionerSuffix(input, "targetEntityAttribute.1.translateFromGrouperProvisioningEntityField", "email");

    configureProvisionerSuffix(input, "numberOfEntityAttributes", "2");

    configureProvisionerSuffix(input, "hasTargetEntityLink", "true");
    configureProvisionerSuffix(input, "entityMatchingAttributeCount", "1");
    configureProvisionerSuffix(input, "entityMatchingAttribute0name", "email");

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

    new GrouperDbConfig().configFileName("grouper-loader.properties").propertyName("otherJob.provisioner_full_" + configId + ".class").value(GrouperProvisioningFullSyncJob.class.getName()).store();
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
