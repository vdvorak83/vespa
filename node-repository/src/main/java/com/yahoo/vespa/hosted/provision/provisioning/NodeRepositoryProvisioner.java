// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.google.inject.Inject;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.HostFilter;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.ProvisionLogger;
import com.yahoo.config.provision.Provisioner;
import com.yahoo.config.provision.Zone;
import com.yahoo.transaction.Mutex;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.flags.IntFlag;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.applications.Application;
import com.yahoo.vespa.hosted.provision.autoscale.AllocatableClusterResources;
import com.yahoo.vespa.hosted.provision.autoscale.AllocationOptimizer;
import com.yahoo.vespa.hosted.provision.autoscale.Limits;
import com.yahoo.vespa.hosted.provision.autoscale.ResourceTarget;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.filter.ApplicationFilter;
import com.yahoo.vespa.hosted.provision.node.filter.NodeHostFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of the host provisioner API for hosted Vespa, using the node repository to allocate nodes.
 * Does not allocate hosts for the routing application, see VespaModelFactory.createHostProvisioner
 *
 * @author bratseth
 */
public class NodeRepositoryProvisioner implements Provisioner {

    private static final Logger log = Logger.getLogger(NodeRepositoryProvisioner.class.getName());

    private final NodeRepository nodeRepository;
    private final AllocationOptimizer allocationOptimizer;
    private final CapacityPolicies capacityPolicies;
    private final Zone zone;
    private final Preparer preparer;
    private final Activator activator;
    private final Optional<LoadBalancerProvisioner> loadBalancerProvisioner;
    private final NodeResourceLimits nodeResourceLimits;
    private final IntFlag tenantNodeQuota;

    @Inject
    public NodeRepositoryProvisioner(NodeRepository nodeRepository, Zone zone,
                                     ProvisionServiceProvider provisionServiceProvider, FlagSource flagSource) {
        this.nodeRepository = nodeRepository;
        this.allocationOptimizer = new AllocationOptimizer(nodeRepository);
        this.capacityPolicies = new CapacityPolicies(nodeRepository);
        this.zone = zone;
        this.loadBalancerProvisioner = provisionServiceProvider.getLoadBalancerService(nodeRepository)
                                                               .map(lbService -> new LoadBalancerProvisioner(nodeRepository, lbService, flagSource));
        this.nodeResourceLimits = new NodeResourceLimits(nodeRepository);
        this.preparer = new Preparer(nodeRepository,
                                     flagSource,
                                     provisionServiceProvider.getHostProvisioner(),
                                     loadBalancerProvisioner);
        this.activator = new Activator(nodeRepository, loadBalancerProvisioner);
        this.tenantNodeQuota = Flags.TENANT_NODE_QUOTA.bindTo(flagSource);
    }


    /**
     * Returns a list of nodes in the prepared or active state, matching the given constraints.
     * The nodes are ordered by increasing index number.
     */
    @Override
    public List<HostSpec> prepare(ApplicationId application, ClusterSpec cluster, Capacity requested,
                                  ProvisionLogger logger) {
        log.log(zone.system().isCd() ? Level.INFO : Level.FINE,
                () -> "Received deploy prepare request for " + requested +
                      " for application " + application + ", cluster " + cluster);

        if (cluster.group().isPresent()) throw new IllegalArgumentException("Node requests cannot specify a group");

        if ( ! hasQuota(application, requested.maxResources().nodes()))
            throw new IllegalArgumentException(requested + " requested for " + cluster +
                                               ". Max value exceeds your quota. Resolve this at https://cloud.vespa.ai/pricing");

        nodeResourceLimits.ensureWithinAdvertisedLimits("Min", requested.minResources().nodeResources(), cluster);
        nodeResourceLimits.ensureWithinAdvertisedLimits("Max", requested.maxResources().nodeResources(), cluster);

        int groups;
        NodeResources resources;
        NodeSpec nodeSpec;
        if ( requested.type() == NodeType.tenant) {
            ClusterResources target = decideTargetResources(application, cluster, requested);
            int nodeCount = capacityPolicies.decideSize(target.nodes(), requested, cluster, application);
            groups = Math.min(target.groups(), nodeCount); // cannot have more groups than nodes
            resources = capacityPolicies.decideNodeResources(target.nodeResources(), requested, cluster);
            boolean exclusive = capacityPolicies.decideExclusivity(cluster.isExclusive());
            nodeSpec = NodeSpec.from(nodeCount, resources, exclusive, requested.canFail());
            logIfDownscaled(target.nodes(), nodeCount, cluster, logger);
        }
        else {
            groups = 1; // type request with multiple groups is not supported
            resources = requested.minResources().nodeResources();
            nodeSpec = NodeSpec.from(requested.type());
        }
        return asSortedHosts(preparer.prepare(application, cluster, nodeSpec, groups), resources);
    }

    @Override
    public void activate(NestedTransaction transaction, ApplicationId application, Collection<HostSpec> hosts) {
        validate(hosts);
        activator.activate(application, hosts, transaction);
    }

    @Override
    public void restart(ApplicationId application, HostFilter filter) {
        nodeRepository.restart(ApplicationFilter.from(application, NodeHostFilter.from(filter)));
    }

    @Override
    public void remove(NestedTransaction transaction, ApplicationId application) {
        nodeRepository.deactivate(application, transaction);
        loadBalancerProvisioner.ifPresent(lbProvisioner -> lbProvisioner.deactivate(application, transaction));
    }

    /**
     * Returns the target cluster resources, a value between the min and max in the requested capacity,
     * and updates the application store with the received min and max.
     */
    private ClusterResources decideTargetResources(ApplicationId applicationId, ClusterSpec clusterSpec, Capacity requested) {
        try (Mutex lock = nodeRepository.lock(applicationId)) {
            Application application = nodeRepository.applications().get(applicationId).orElse(new Application(applicationId));
            application = application.withCluster(clusterSpec.id(), clusterSpec.isExclusive(), requested.minResources(), requested.maxResources());
            nodeRepository.applications().put(application, lock);
            return application.clusters().get(clusterSpec.id()).targetResources()
                    .orElseGet(() -> currentResources(applicationId, clusterSpec, requested));
        }
    }

    /** Returns the current resources of this cluster, or the closes  */
    private ClusterResources currentResources(ApplicationId applicationId,
                                              ClusterSpec clusterSpec,
                                              Capacity requested) {
        List<Node> nodes = NodeList.copyOf(nodeRepository.getNodes(applicationId, Node.State.active))
                                   .cluster(clusterSpec.id())
                                   .not().retired()
                                   .not().removable()
                                   .asList();
        boolean firstDeployment = nodes.isEmpty();
        AllocatableClusterResources currentResources =
                firstDeployment // start at min, preserve current resources otherwise
                ? new AllocatableClusterResources(requested.minResources(), clusterSpec.type(), nodeRepository)
                : new AllocatableClusterResources(nodes, nodeRepository);
        return within(Limits.of(requested), clusterSpec.isExclusive(), currentResources, firstDeployment);
    }

    /** Make the minimal adjustments needed to the current resources to stay within the limits */
    private ClusterResources within(Limits limits,
                                    boolean exclusive,
                                    AllocatableClusterResources current,
                                    boolean firstDeployment) {
        if (limits.min().equals(limits.max())) return limits.min();

        // Don't change current deployments that are still legal
        var currentAsAdvertised = current.toAdvertisedClusterResources();
        if (! firstDeployment && currentAsAdvertised.isWithin(limits.min(), limits.max())) return currentAsAdvertised;

        // Otherwise, find an allocation that preserves the current resources as well as possible
        return allocationOptimizer.findBestAllocation(ResourceTarget.preserve(current), current, limits, exclusive)
                                  .orElseThrow(() -> new IllegalArgumentException("No allocation possible within " + limits))
                                  .toAdvertisedClusterResources();
    }

    private void logIfDownscaled(int targetNodes, int actualNodes, ClusterSpec cluster, ProvisionLogger logger) {
        if (zone.environment().isManuallyDeployed() && actualNodes < targetNodes)
            logger.log(Level.INFO, "Requested " + targetNodes + " nodes for " + cluster +
                                   ", downscaling to " + actualNodes + " nodes in " + zone.environment());
    }

    private boolean hasQuota(ApplicationId application, int requestedNodes) {
        if ( ! this.zone.system().isPublic()) return true; // no quota management

        if (application.tenant().value().hashCode() == 3857)        return requestedNodes <= 60;
        if (application.tenant().value().hashCode() == -1271827001) return requestedNodes <= 75;

        return requestedNodes <= tenantNodeQuota.with(FetchVector.Dimension.APPLICATION_ID, application.tenant().value()).value();
    }

    private List<HostSpec> asSortedHosts(List<Node> nodes, NodeResources requestedResources) {
        nodes.sort(Comparator.comparingInt(node -> node.allocation().get().membership().index()));
        List<HostSpec> hosts = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            log.log(Level.FINE, () -> "Prepared node " + node.hostname() + " - " + node.flavor());
            Allocation nodeAllocation = node.allocation().orElseThrow(IllegalStateException::new);
            hosts.add(new HostSpec(node.hostname(),
                                   nodeRepository.resourcesCalculator().realResourcesOf(node, nodeRepository),
                                   node.flavor().resources(),
                                   requestedResources,
                                   nodeAllocation.membership(),
                                   node.status().vespaVersion(),
                                   nodeAllocation.networkPorts(),
                                   node.status().dockerImage()));
            if (nodeAllocation.networkPorts().isPresent()) {
                log.log(Level.FINE, () -> "Prepared node " + node.hostname() + " has port allocations");
            }
        }
        return hosts;
    }

    private void validate(Collection<HostSpec> hosts) {
        for (HostSpec host : hosts) {
            if (host.membership().isEmpty())
                throw new IllegalArgumentException("Hosts must be assigned a cluster when activating, but got " + host);
            if (host.membership().get().cluster().group().isEmpty())
                throw new IllegalArgumentException("Hosts must be assigned a group when activating, but got " + host);
        }
    }

}
