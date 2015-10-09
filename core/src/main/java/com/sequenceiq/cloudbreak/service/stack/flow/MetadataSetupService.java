package com.sequenceiq.cloudbreak.service.stack.flow;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sequenceiq.cloudbreak.common.type.BillingStatus;
import com.sequenceiq.cloudbreak.common.type.CloudPlatform;
import com.sequenceiq.cloudbreak.domain.InstanceGroup;
import com.sequenceiq.cloudbreak.common.type.InstanceGroupType;
import com.sequenceiq.cloudbreak.domain.InstanceMetaData;
import com.sequenceiq.cloudbreak.common.type.InstanceStatus;
import com.sequenceiq.cloudbreak.domain.Resource;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.repository.InstanceGroupRepository;
import com.sequenceiq.cloudbreak.repository.InstanceMetaDataRepository;
import com.sequenceiq.cloudbreak.service.CloudPlatformResolver;
import com.sequenceiq.cloudbreak.service.events.CloudbreakEventService;
import com.sequenceiq.cloudbreak.service.messages.CloudbreakMessagesService;
import com.sequenceiq.cloudbreak.service.stack.StackService;

@Service
public class MetadataSetupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataSetupService.class);

    @Inject
    private CloudPlatformResolver cloudPlatformResolver;

    @Inject
    private InstanceGroupRepository instanceGroupRepository;

    @Inject
    private InstanceMetaDataRepository instanceMetaDataRepository;

    @Inject
    private StackService stackService;

    @Inject
    private CloudbreakEventService eventService;

    @Inject
    private CloudbreakMessagesService cloudbreakMessagesService;

    private enum Msg {
        STACK_METADATA_SETUP_UPSCALING_BILLING_CHANGED("stack.metadata.setup.billing.changed");

        private String code;

        Msg(String msgCode) {
            code = msgCode;
        }

        public String code() {
            return code;
        }
    }

    public String setupMetadata(final CloudPlatform cloudPlatform, Stack stack) throws Exception {
        Set<InstanceMetaData> allInstanceMetadata = saveInstanceMetaData(stack, collectCoreMetadata(cloudPlatform, stack), true);
        for (InstanceMetaData instanceMetaData : allInstanceMetadata) {
            if (instanceMetaData.getAmbariServer()) {
                return instanceMetaData.getPublicIp();
            }
        }
        return null;
    }

    public void collectMetadata(final CloudPlatform cloudPlatform, Stack stack) {
        saveInstanceMetaData(stack, collectCoreMetadata(cloudPlatform, stack), false);
    }

    public Set<String> setupNewMetadata(Long stackId, Set<Resource> resources, String instanceGroupName, Integer scalingAdjustment) {
        Stack stack = stackService.getById(stackId);
        Set<CoreInstanceMetaData> coreInstanceMetaData = collectNewMetadata(stack, resources, instanceGroupName, scalingAdjustment);
        saveInstanceMetaData(stack, coreInstanceMetaData, true);
        Set<String> upscaleCandidateAddresses = new HashSet<>();
        for (CoreInstanceMetaData coreInstanceMetadataEntry : coreInstanceMetaData) {
            upscaleCandidateAddresses.add(coreInstanceMetadataEntry.getPrivateIp());
        }
        InstanceGroup instanceGroup = instanceGroupRepository.findOneByGroupNameInStack(stack.getId(), instanceGroupName);
        int nodeCount = instanceGroup.getNodeCount() + coreInstanceMetaData.size();
        instanceGroup.setNodeCount(nodeCount);
        instanceGroupRepository.save(instanceGroup);
        eventService.fireCloudbreakEvent(stack.getId(), BillingStatus.BILLING_CHANGED.name(),
                cloudbreakMessagesService.getMessage(Msg.STACK_METADATA_SETUP_UPSCALING_BILLING_CHANGED.code));
        return upscaleCandidateAddresses;
    }

    private Set<CoreInstanceMetaData> collectCoreMetadata(CloudPlatform cloudPlatform, Stack stack) {
        Set<CoreInstanceMetaData> coreInstanceMetaData = cloudPlatformResolver.metadata(cloudPlatform).collectMetadata(stack);
        if (coreInstanceMetaData.size() != stack.getFullNodeCount()) {
            throw new WrongMetadataException(String.format(
                    "Size of the collected metadata set does not equal the node count of the stack. [metadata size=%s] [nodecount=%s]",
                    coreInstanceMetaData.size(), stack.getFullNodeCount()));
        }
        return coreInstanceMetaData;
    }

    private Set<InstanceMetaData> saveInstanceMetaData(Stack stack, Set<CoreInstanceMetaData> coreInstanceMetaData, Boolean updateStatus) {
        Boolean ambariServerFound = false;
        Set<InstanceMetaData> updatedInstanceMetadata = new HashSet<>();
        Set<InstanceMetaData> allInstanceMetadata = instanceMetaDataRepository.findNotTerminatedForStack(stack.getId());
        for (CoreInstanceMetaData coreInstanceMetadataEntry : coreInstanceMetaData) {
            long timeInMillis = Calendar.getInstance().getTimeInMillis();
            InstanceGroup instanceGroup = instanceGroupRepository.findOneByGroupNameInStack(stack.getId(), coreInstanceMetadataEntry.getInstanceGroupName());
            boolean gateway = InstanceGroupType.GATEWAY.equals(instanceGroup.getInstanceGroupType());
            Long privateId = coreInstanceMetadataEntry.getPrivateId();
            InstanceMetaData instanceMetaDataEntry = createInstanceMetadataIfAbsent(allInstanceMetadata, privateId);
            instanceMetaDataEntry.setPrivateIp(coreInstanceMetadataEntry.getPrivateIp());
            instanceMetaDataEntry.setInstanceGroup(instanceGroup);
            instanceMetaDataEntry.setPublicIp(coreInstanceMetadataEntry.getPublicIp());
            instanceMetaDataEntry.setInstanceId(coreInstanceMetadataEntry.getInstanceId());
            instanceMetaDataEntry.setPrivateId(privateId);
            instanceMetaDataEntry.setVolumeCount(coreInstanceMetadataEntry.getVolumeCount());
            instanceMetaDataEntry.setDockerSubnet(null);
            instanceMetaDataEntry.setContainerCount(coreInstanceMetadataEntry.getContainerCount());
            instanceMetaDataEntry.setStartDate(timeInMillis);
            if (!ambariServerFound && gateway) {
                instanceMetaDataEntry.setAmbariServer(Boolean.TRUE);
                ambariServerFound = true;
            } else {
                instanceMetaDataEntry.setAmbariServer(Boolean.FALSE);
            }
            if (updateStatus) {
                instanceMetaDataEntry.setInstanceStatus(gateway ? InstanceStatus.REGISTERED : InstanceStatus.UNREGISTERED);
            }
            instanceMetaDataRepository.save(instanceMetaDataEntry);
            updatedInstanceMetadata.add(instanceMetaDataEntry);
        }
        return updatedInstanceMetadata;
    }

    private InstanceMetaData createInstanceMetadataIfAbsent(Set<InstanceMetaData> allInstanceMetadata, Long privateId) {
        if (privateId != null) {
            for (InstanceMetaData instanceMetaData : allInstanceMetadata) {
                if (Objects.equals(instanceMetaData.getPrivateId(), privateId)) {
                    return instanceMetaData;
                }
            }
        }
        return new InstanceMetaData();
    }

    private Set<CoreInstanceMetaData> collectNewMetadata(Stack stack, Set<Resource> resources, String instanceGroup, Integer scalingAdjustment) {
        try {
            return cloudPlatformResolver.metadata(stack.cloudPlatform()).collectNewMetadata(stack, resources, instanceGroup, scalingAdjustment);
        } catch (Exception e) {
            LOGGER.error("Unhandled exception occurred while updating stack metadata.", e);
            throw e;
        }
    }
}
