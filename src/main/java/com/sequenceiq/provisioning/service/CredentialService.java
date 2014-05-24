package com.sequenceiq.provisioning.service;

import com.sequenceiq.provisioning.controller.json.CredentialJson;
import com.sequenceiq.provisioning.domain.CloudPlatform;
import com.sequenceiq.provisioning.domain.User;

public interface CredentialService {

    void saveCredentials(User user, CredentialJson credentialRequest);

    CredentialJson retrieveCredentials(User user);

    CloudPlatform getCloudPlatform();

}