package edu.internet2.middleware.grouper.app.teamsChannels;

import java.util.ArrayList;
import java.util.List;

import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningObjectMetadata;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningObjectMetadataItem;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningObjectMetadataItemFormElementType;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningObjectMetadataItemValueType;
import edu.internet2.middleware.grouper.cfg.text.GrouperTextContainer;
import edu.internet2.middleware.grouperClient.collections.MultiKey;

/**
 * Built-in metadata for the Teams channel provisioner.  Two items are offered:
 *
 *   md_grouper_teamId         - the parent team (M365 group) id a channel lives in
 *   md_grouper_membershipType - standard | private | shared (set at create time)
 *
 * These let a Grouper admin tell the provisioner which team a group's channel
 * belongs to and what kind of channel to create, per group or folder.
 *
 * Modeled on AzureSyncObjectMetadata.
 */
public class TeamsChannelSyncObjectMetadata extends GrouperProvisioningObjectMetadata {

  public TeamsChannelSyncObjectMetadata() {
  }

  @Override
  public void initBuiltInMetadata() {
    super.initBuiltInMetadata();

    GrouperTeamsChannelConfiguration configuration =
        (GrouperTeamsChannelConfiguration) this.getGrouperProvisioner().retrieveGrouperProvisioningConfiguration();

    if (configuration.isTeamIdMetadata() && !this.containsMetadataItemByName("md_grouper_teamId")) {
      GrouperProvisioningObjectMetadataItem item = new GrouperProvisioningObjectMetadataItem();
      item.setDescriptionKey("grouperProvisioningMetadataTeamsChannelTeamIdDescription");
      item.setLabelKey("grouperProvisioningMetadataTeamsChannelTeamIdLabel");
      item.setName("md_grouper_teamId");
      item.setShowForGroup(true);
      item.setShowForFolder(true);
      item.setCanUpdate(false);
      item.setValueType(GrouperProvisioningObjectMetadataItemValueType.STRING);
      item.setFormElementType(GrouperProvisioningObjectMetadataItemFormElementType.TEXT);
      this.getGrouperProvisioningObjectMetadataItems().add(item);
    }

    if (configuration.isMembershipTypeMetadata() && !this.containsMetadataItemByName("md_grouper_membershipType")) {
      GrouperProvisioningObjectMetadataItem item = new GrouperProvisioningObjectMetadataItem();
      item.setDescriptionKey("grouperProvisioningMetadataTeamsChannelMembershipTypeDescription");
      item.setLabelKey("grouperProvisioningMetadataTeamsChannelMembershipTypeLabel");
      item.setName("md_grouper_membershipType");
      item.setShowForGroup(true);
      item.setShowForFolder(true);
      // membershipType is immutable once a channel exists
      item.setCanUpdate(false);
      item.setValueType(GrouperProvisioningObjectMetadataItemValueType.STRING);
      item.setFormElementType(GrouperProvisioningObjectMetadataItemFormElementType.DROPDOWN);

      List<MultiKey> valuesAndLabels = new ArrayList<MultiKey>();
      valuesAndLabels.add(new MultiKey("standard", GrouperTextContainer.textOrNull("config.teamsChannelMembershipTypeStandard")));
      valuesAndLabels.add(new MultiKey("private", GrouperTextContainer.textOrNull("config.teamsChannelMembershipTypePrivate")));
      valuesAndLabels.add(new MultiKey("shared", GrouperTextContainer.textOrNull("config.teamsChannelMembershipTypeShared")));
      item.setKeysAndLabelsForDropdown(valuesAndLabels);

      this.getGrouperProvisioningObjectMetadataItems().add(item);
    }
  }
}
