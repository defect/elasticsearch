package org.apache.mesos.elasticsearch.systemtest;

import com.containersol.minimesos.mesos.MesosSlave;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import org.apache.log4j.Logger;
import org.apache.mesos.elasticsearch.common.cli.ElasticsearchCLIParameter;
import org.apache.mesos.elasticsearch.common.cli.ZookeeperCLIParameter;
import org.apache.mesos.elasticsearch.scheduler.Configuration;
import com.containersol.minimesos.MesosCluster;
import com.containersol.minimesos.mesos.MesosClusterConfig;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.jayway.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests cluster state monitoring and reconciliation.
 */
@SuppressWarnings({"PMD.TooManyMethods"})
public class ReconciliationSystemTest {
    private static final Logger LOGGER = Logger.getLogger(ReconciliationSystemTest.class);
    @ClassRule
    public static final MesosCluster CLUSTER = new MesosCluster(
        MesosClusterConfig.builder()
                .mesosImageTag(Main.MESOS_IMAGE_TAG)
                .slaveResources(new String[]{"ports(*):[9200-9200,9300-9300]", "ports(*):[9201-9201,9301-9301]", "ports(*):[9202-9202,9302-9302]"})
                .build()
    );
    private static final org.apache.mesos.elasticsearch.systemtest.Configuration TEST_CONFIG = new org.apache.mesos.elasticsearch.systemtest.Configuration();

    private static final int TIMEOUT = 60;
    private static final String MESOS_LOCAL_IMAGE_NAME = "mesos-local";
    private static final ContainerLifecycleManagement CONTAINER_MANAGER = new ContainerLifecycleManagement();
    private static String mesosClusterId;

    private static ElasticsearchSchedulerContainer startSchedulerContainer() {
        ElasticsearchSchedulerContainer scheduler = new ElasticsearchSchedulerContainer(CLUSTER.getConfig().dockerClient, CLUSTER.getZkContainer().getIpAddress());
        CONTAINER_MANAGER.addAndStart(scheduler);
        return scheduler;
    }

    @After
    public void after() throws IOException {
        CONTAINER_MANAGER.stopAll();
        getContainers().forEach(container -> CLUSTER.getConfig().dockerClient.killContainerCmd(container.getId()).exec());
    }

    @Test
    public void forceCheckExecutorTimeout() throws IOException {
        ElasticsearchSchedulerContainer scheduler = new TimeoutSchedulerContainer(CLUSTER.getConfig().dockerClient, CLUSTER.getZkContainer().getIpAddress());
        CONTAINER_MANAGER.addAndStart(scheduler);
        assertCorrectNumberOfExecutors(); // Start with 3
        assertLessThan(TEST_CONFIG.getElasticsearchNodesCount()); // Then should be less than 3, because at some point we kill an executor
        assertCorrectNumberOfExecutors(); // Then at some point should get back to 3.
    }

    @Test
    public void ifSchedulerLostShouldReconcileExecutors() throws IOException {
        ElasticsearchSchedulerContainer scheduler = startSchedulerContainer();
        assertCorrectNumberOfExecutors();

        // Stop and restart container
        CONTAINER_MANAGER.stopContainer(scheduler);
        startSchedulerContainer();
        assertCorrectNumberOfExecutors();
    }

    @Test
    public void ifExecutorIsLostShouldStartAnother() throws IOException {
        startSchedulerContainer();
        assertCorrectNumberOfExecutors();

        killOneExecutor();

        // Should restart an executor, so there should still be three
        assertCorrectNumberOfExecutors();

    }

    @Test
    public void ifExecutorIsLostWhileSchedulerIsDead() throws IOException {
        ElasticsearchSchedulerContainer scheduler = startSchedulerContainer();
        assertCorrectNumberOfExecutors();

        // Kill scheduler
        CONTAINER_MANAGER.stopContainer(scheduler);

        // Kill executor
        killOneExecutor();

        startSchedulerContainer();
        // Should restart an executor, so there should still be three
        assertCorrectNumberOfExecutors();
    }

    private void assertCorrectNumberOfExecutors() throws IOException {
        assertCorrectNumberOfExecutors(TEST_CONFIG.getElasticsearchNodesCount());
    }

    private void assertLessThan(int expected) throws IOException {
        await().atMost(TIMEOUT, TimeUnit.SECONDS).until(() -> clusterPs().size() < expected);
        List<Container> result = getContainers();
        assertTrue(result.size() < expected);
    }

    private List<Container> getContainers() throws IOException {
        List<Container> result = clusterPs();
        LOGGER.debug("Mini-mesos PS command = " + Arrays.toString(result.stream().map(Container::getId).collect(Collectors.toList()).toArray()));
        return result;
    }

    private void assertCorrectNumberOfExecutors(int expected) throws IOException {
        await().atMost(TIMEOUT, TimeUnit.SECONDS).until(() -> clusterPs().size() == expected);
        List<Container> result = getContainers();
        assertEquals(expected, result.size());
    }

    private void killOneExecutor() throws IOException {
        String executorId = getLastExecutor();
        LOGGER.debug("Killing container: " + executorId);
        CLUSTER.getConfig().dockerClient.killContainerCmd(executorId).exec();
    }

    // Note: we cant use the task response again because the tasks are only added when created.
    private List<Container> clusterPs() throws IOException {
        return CLUSTER.getConfig().dockerClient.listContainersCmd().exec().stream().filter(container -> container.getImage().contains("elasticsearch-executor")).collect(Collectors.toList());
    }

    private String getLastExecutor() throws IOException {
        return clusterPs().get(0).getId();
    }

    private static class TimeoutSchedulerContainer extends ElasticsearchSchedulerContainer {
        protected TimeoutSchedulerContainer(DockerClient dockerClient, String zkIp) {
            super(dockerClient, zkIp);
        }

        @Override
        protected CreateContainerCmd dockerCommand() {
            return dockerClient
                    .createContainerCmd(TEST_CONFIG.getSchedulerImageName())
                    .withName(TEST_CONFIG.getSchedulerName() + "_" + new SecureRandom().nextInt())
                    .withEnv("JAVA_OPTS=-Xms128m -Xmx256m")
                    .withCmd(
                            ZookeeperCLIParameter.ZOOKEEPER_MESOS_URL, getZookeeperMesosUrl(),
                            Configuration.EXECUTOR_HEALTH_DELAY, "99",
                            Configuration.EXECUTOR_TIMEOUT, "100", // This timeout is valid, but will always timeout, because of delays in receiving healthchecks.
                            ElasticsearchCLIParameter.ELASTICSEARCH_NODES, "3",
                            Configuration.ELASTICSEARCH_RAM, "256"
                    );
        }
    }

}
