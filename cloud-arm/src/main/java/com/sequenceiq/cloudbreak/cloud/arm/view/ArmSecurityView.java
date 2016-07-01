package com.sequenceiq.cloudbreak.cloud.arm.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sequenceiq.cloudbreak.cloud.model.Group;
import com.sequenceiq.cloudbreak.cloud.model.SecurityRule;
import com.sequenceiq.cloudbreak.cloud.model.Subnet;

public class ArmSecurityView {

    private Map<String, List<ArmPortView>> ports = new HashMap<>();
    private Map<String, String> ads = new HashMap<>();

    public ArmSecurityView(List<Group> groups, Subnet subnet) {
        int i = 0;
        for (Group group : groups) {
            List<ArmPortView> groupPorts = new ArrayList<>();
            for (SecurityRule securityRule : group.getSecurity().getRules()) {
                for (String port : securityRule.getPorts()) {
                    groupPorts.add(new ArmPortView(securityRule.getCidr(), port, securityRule.getProtocol()));
                }
            }
            ports.put(group.getName(), groupPorts);
            ads.put(group.getName(), calculateSubnetAddress(subnet.getCidr(), i));
            i++;
        }
    }

    private String calculateSubnetAddress(String cidr, int i) {
        String tmp[] = cidr.split("\\.");
        Long changer = Long.valueOf(tmp[2]);
        return String.format("%s.%s.%s.%s", tmp[0], tmp[1], (changer + i) > 255 ? (changer + i) % 255 : (changer + i), tmp[3].replace("/16", "/24"));
    }

    public Map<String, List<ArmPortView>> getPorts() {
        return ports;
    }

    public Map<String, String> getAds() {
        return ads;
    }
}