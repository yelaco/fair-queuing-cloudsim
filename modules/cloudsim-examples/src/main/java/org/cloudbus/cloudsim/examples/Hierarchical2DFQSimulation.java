package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.util.*;
import java.util.stream.Collectors;

public class Hierarchical2DFQSimulation {
    private static final int NUM_VMS = 3;
    private static final int NUM_THREADS_PER_VM = 2;
    private static final int NUM_CLOUDLETS = 30;
    private static final int DEADLINE_THRESHOLD = 400;
    private static final int LENGTH_THRESHOLD = 600;
    private static final int BATCH_SIZE = NUM_THREADS_PER_VM * NUM_VMS;

    private static class TrackedCloudlet extends Cloudlet {
        public enum QueueType { DEADLINE, COST }

        private int threadId;
        private QueueType queueType;
        private boolean deadlineMet;
        private double startTag;
        private double finishTag;
        private double virtualStart;

        public TrackedCloudlet(int cloudletId, long length, int pesNumber, long fileSize, long outputSize,
                               UtilizationModel utilizationModel, QueueType queueType) {
            super(cloudletId, length, pesNumber, fileSize, outputSize,
                    utilizationModel, utilizationModel, utilizationModel);
            this.queueType = queueType;
        }

        public void setThreadId(int id) { this.threadId = id; }
        public int getThreadId() { return threadId; }

        public void setQueueType(QueueType queueType) { this.queueType = queueType; }
        public QueueType getQueueType() { return queueType; }

        public void setDeadlineMet(boolean met) { this.deadlineMet = met; }
        public boolean isDeadlineMet() { return deadlineMet; }

        public void setStartTag(double tag) { this.startTag = tag; }
        public void setFinishTag(double tag) { this.finishTag = tag; }
        public double getFinishTag() { return this.finishTag; }

        public void setVirtualStart(double virtualStart) { this.virtualStart = virtualStart; }
    }

    private static class VMWrapper {
        public Vm vm;
        public double costPerSec;
        public double virtualTime = 0;
        public List<PriorityQueue<TrackedCloudlet>> threadQueues = new ArrayList<>();

        public VMWrapper(Vm vm, double costPerSec) {
            this.vm = vm;
            this.costPerSec = costPerSec;
            for (int i = 0; i < NUM_THREADS_PER_VM; i++) {
                threadQueues.add(new PriorityQueue<>(Comparator.comparingDouble(TrackedCloudlet::getFinishTag)));
            }
        }

        public void updateVirtualTime(List<TrackedCloudlet> completedCloudlets) {
            double sumLengths = completedCloudlets.stream().mapToDouble(Cloudlet::getCloudletLength).sum();
            virtualTime += sumLengths / NUM_THREADS_PER_VM;
        }
    }

    private static class MetricsCollector {
        private static final List<TrackedCloudlet> completedTasks = new ArrayList<>();

        public static void logCompletion(TrackedCloudlet cloudlet) {
            completedTasks.add(cloudlet);
        }

        public static void printSummary() {
            long metDeadline = completedTasks.stream().filter(TrackedCloudlet::isDeadlineMet).count();
            long missedDeadline = completedTasks.size() - metDeadline;
            System.out.println("===== Simulation Summary =====");
            System.out.println("Total Cloudlets: " + completedTasks.size());
            System.out.println("Met Deadline: " + metDeadline);
            System.out.println("Missed Deadline: " + missedDeadline);
        }
    }

    // public static void main(String[] args) throws Exception {
    //     CloudSim.init(1, Calendar.getInstance(), false);
    //     Datacenter datacenter = createDatacenter("Datacenter_0");
    //     DatacenterBroker broker = createBroker();
    //     int brokerId = broker.getId();
    //
    //     List<VMWrapper> vmWrappers = new ArrayList<>();
    //     for (int i = 0; i < NUM_VMS; i++) {
    //         Vm vm = new Vm(i, brokerId, 1000, NUM_THREADS_PER_VM, 2048, 10000, 10000,
    //                 "Xen", new CloudletSchedulerTimeShared());
    //         vmWrappers.add(new VMWrapper(vm, 0.01 + i * 0.01));
    //     }
    //     broker.submitGuestList(vmWrappers.stream().map(w -> w.vm).collect(Collectors.toList()));
    //
    //     List<TrackedCloudlet> cloudletList = new ArrayList<>();
    //     for (int i = 0; i < NUM_CLOUDLETS; i++) {
    //         long length = 400 + (int)(Math.random() * 600);
    //         TrackedCloudlet.QueueType queueType =
    //                 length <= DEADLINE_THRESHOLD ? TrackedCloudlet.QueueType.DEADLINE : TrackedCloudlet.QueueType.COST;
    //         TrackedCloudlet cloudlet = new TrackedCloudlet(i, length, 1, 300, 300, new UtilizationModelFull(), queueType);
    //         cloudlet.setUserId(brokerId);
    //         cloudletList.add(cloudlet);
    //     }
    //
    //     for (TrackedCloudlet c : cloudletList) {
    //         if (c.getQueueType() == TrackedCloudlet.QueueType.DEADLINE) {
    //             assignToDeadlineQueue(c, vmWrappers);
    //         } else {
    //             assignToCostQueue(c, vmWrappers);
    //         }
    //     }
    //
    //     List<Cloudlet> allScheduledCloudlets = new ArrayList<>();
    //     for (VMWrapper vmWrapper : vmWrappers) {
    //         for (int i = 0; i < vmWrapper.threadQueues.size(); i++) {
    //             for (TrackedCloudlet c : vmWrapper.threadQueues.get(i)) {
    //                 allScheduledCloudlets.add(c);
    //             }
    //         }
    //     }
    //
    //     broker.submitCloudletList(allScheduledCloudlets);
    //     CloudSim.startSimulation();
    //
    //     List<Cloudlet> received = broker.getCloudletReceivedList();
    //     for (Cloudlet cloudlet : received) {
    //         TrackedCloudlet tracked = (TrackedCloudlet) cloudlet;
    //         boolean met = (tracked.getFinishTime() <= tracked.getSubmissionTime() + DEADLINE_THRESHOLD);
    //         tracked.setDeadlineMet(met);
    //         MetricsCollector.logCompletion(tracked);
    //         System.out.printf("Cloudlet %d executed on VM %d, Thread %d, FinishTag: %.2f, Status: %s\n",
    //                 tracked.getCloudletId(), tracked.getVmId(), tracked.getThreadId(),
    //                 tracked.getFinishTag(),
    //                 tracked.getStatus() == Cloudlet.CloudletStatus.SUCCESS ? "SUCCESS" : "FAIL");
    //     }
    //
    //     CloudSim.stopSimulation();
    //     MetricsCollector.printSummary();
    // }
    //
  public static void main(String[] args) throws Exception {
    CloudSim.init(1, Calendar.getInstance(), false);
    Datacenter datacenter = createDatacenter("Datacenter_0");
    DatacenterBroker broker = createBroker();
    int brokerId = broker.getId();

    List<VMWrapper> vmWrappers = new ArrayList<>();
    for (int i = 0; i < NUM_VMS; i++) {
        Vm vm = new Vm(i, brokerId, 1000, NUM_THREADS_PER_VM, 2048, 10000, 10000,
                "Xen", new CloudletSchedulerTimeShared());
        vmWrappers.add(new VMWrapper(vm, 0.01 + i * 0.01));
    }
    broker.submitGuestList(vmWrappers.stream().map(w -> w.vm).collect(Collectors.toList()));

    List<TrackedCloudlet> cloudletList = new ArrayList<>();
    for (int i = 0; i < NUM_CLOUDLETS; i++) {
        long length = 400 + (int)(Math.random() * 600);
        TrackedCloudlet.QueueType queueType =
                length <= DEADLINE_THRESHOLD ? TrackedCloudlet.QueueType.DEADLINE : TrackedCloudlet.QueueType.COST;
        TrackedCloudlet cloudlet = new TrackedCloudlet(i, length, 1, 300, 300, new UtilizationModelFull(), queueType);
        cloudlet.setUserId(brokerId);
        cloudletList.add(cloudlet);
    }

    int batchCount = (int) Math.ceil((double) cloudletList.size() / BATCH_SIZE);
    for (int batchIndex = 0; batchIndex < batchCount; batchIndex++) {
        System.out.println("\n=== Submitting Batch " + (batchIndex + 1) + " ===");
        int from = batchIndex * BATCH_SIZE;
        int to = Math.min(from + BATCH_SIZE, cloudletList.size());
        List<TrackedCloudlet> batch = cloudletList.subList(from, to);

        for (TrackedCloudlet c : batch) {
            if (c.getQueueType() == TrackedCloudlet.QueueType.DEADLINE) {
                assignToDeadlineQueue(c, vmWrappers);
            } else {
                assignToCostQueue(c, vmWrappers);
            }
        }

        List<Cloudlet> scheduledBatch = new ArrayList<>();
        for (VMWrapper vmWrapper : vmWrappers) {
            for (int i = 0; i < vmWrapper.threadQueues.size(); i++) {
                PriorityQueue<TrackedCloudlet> queue = vmWrapper.threadQueues.get(i);
                while (!queue.isEmpty()) {
                    scheduledBatch.add(queue.poll());
                }
            }
        }

        broker.submitCloudletList(scheduledBatch);
        CloudSim.startSimulation();

        List<Cloudlet> received = broker.getCloudletReceivedList();
        for (Cloudlet cloudlet : received) {
            TrackedCloudlet tracked = (TrackedCloudlet) cloudlet;
            boolean met = (tracked.getFinishTime() <= tracked.getSubmissionTime() + DEADLINE_THRESHOLD);
            tracked.setDeadlineMet(met);
            MetricsCollector.logCompletion(tracked);
            System.out.printf("Cloudlet %d executed on VM %d, Thread %d, FinishTag: %.2f, Status: %s\n",
                    tracked.getCloudletId(), tracked.getVmId(), tracked.getThreadId(),
                    tracked.getFinishTag(),
                    tracked.getStatus() == Cloudlet.CloudletStatus.SUCCESS ? "SUCCESS" : "FAIL");
        }

        // Update virtual time per VM based on this batch
        for (VMWrapper vmWrapper : vmWrappers) {
            List<TrackedCloudlet> completed = received.stream()
                    .map(c -> (TrackedCloudlet) c)
                    .filter(c -> c.getVmId() == vmWrapper.vm.getId())
                    .collect(Collectors.toList());
            vmWrapper.updateVirtualTime(completed);
        }
    }

    MetricsCollector.printSummary();
}

    private static void assignToDeadlineQueue(TrackedCloudlet c, List<VMWrapper> vmWrappers) {
        VMWrapper selected = Collections.min(vmWrappers, Comparator.comparingDouble(w -> w.vm.getCloudletScheduler().getPreviousTime()));
        assignToThread2DFQ(selected, c);
    }

    private static void assignToCostQueue(TrackedCloudlet c, List<VMWrapper> vmWrappers) {
        VMWrapper selected = Collections.min(vmWrappers, Comparator.comparingDouble(w -> w.costPerSec));
        assignToThread2DFQ(selected, c);
    }

    private static void assignToThread2DFQ(VMWrapper vmWrapper, TrackedCloudlet cloudlet) {
        int bestThread = -1;
        double bestEligible = Double.MAX_VALUE;
        double bestStart = Double.MAX_VALUE;

        System.out.println("\n--- Assigning Cloudlet " + cloudlet.getCloudletId() + " ---");
        System.out.printf("Cloudlet Length: %d | VM ID: %d | Queue Type: %s\n", 
                      cloudlet.getCloudletLength(), vmWrapper.vm.getId(), cloudlet.getQueueType());
        System.out.printf("VM Virtual Time: %.2f\n", vmWrapper.virtualTime);

        boolean isSmallTask = cloudlet.getCloudletLength() <= LENGTH_THRESHOLD;
        int mid = NUM_THREADS_PER_VM / 2;
        List<Integer> threadIndices = new ArrayList<>();

        if (isSmallTask) {
            System.out.println("Task Type: SMALL => Using HIGH-INDEX threads (top-down)");
            for (int i = NUM_THREADS_PER_VM - 1; i >= mid; i--) {
                threadIndices.add(i);
            }
        } else {
            System.out.println("Task Type: LARGE => Using LOW-INDEX threads (bottom-up)");
            for (int i = 0; i < mid; i++) {
                threadIndices.add(i);
            }
        }

        System.out.printf("Thread Range Considered: %s\n", threadIndices);

        for (int i : threadIndices) {
            PriorityQueue<TrackedCloudlet> queue = vmWrapper.threadQueues.get(i);
            double lastFinish = queue.isEmpty() ? vmWrapper.virtualTime : queue.peek().getFinishTag();
            double start = Math.max(vmWrapper.virtualTime, lastFinish);
            double tEligible = start - (1 - ((double)(i + 1) / NUM_THREADS_PER_VM)) * cloudlet.getCloudletLength();

            System.out.printf("Thread %d => LastFinish: %.2f | Start: %.2f | Eligible: %.2f\n", i, lastFinish, start, tEligible);

            if (tEligible <= vmWrapper.virtualTime) {
                bestStart = start;
                bestThread = i;
                bestEligible = tEligible;
            }
        }

        // Fallback if no thread satisfies fair condition
        if (bestThread == -1) {
            System.out.println("No eligible thread found under fair condition. Using fallback (best eligible).");
            for (int i : threadIndices) {
                PriorityQueue<TrackedCloudlet> queue = vmWrapper.threadQueues.get(i);
                double lastFinish = queue.isEmpty() ? vmWrapper.virtualTime : queue.peek().getFinishTag();
                double start = Math.max(vmWrapper.virtualTime, lastFinish);
                double tEligible = start - (1 - ((double)(i + 1) / NUM_THREADS_PER_VM)) * cloudlet.getCloudletLength();

                if (tEligible < bestEligible) {
                    bestStart = start;
                    bestThread = i;
                    bestEligible = tEligible;
                }
            }
        }

        cloudlet.setThreadId(bestThread);
        cloudlet.setVirtualStart(bestStart);
        cloudlet.setStartTag(bestStart);
        cloudlet.setFinishTag(bestStart + cloudlet.getCloudletLength());
        vmWrapper.threadQueues.get(bestThread).add(cloudlet);

        System.out.printf("=> Assigned to Thread: %d | Virtual Start: %.2f | Start Tag: %.2f | Finish Tag: %.2f\n",
               bestThread, cloudlet.virtualStart, cloudlet.startTag, cloudlet.getFinishTag());
    }


    private static Datacenter createDatacenter(String name) throws Exception {
        List<Host> hostList = new ArrayList<>();
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < NUM_THREADS_PER_VM * NUM_VMS; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(1000)));
        }

        Host host = new Host(0,
                new RamProvisionerSimple(8192),
                new BwProvisionerSimple(100000),
                1000000,
                peList,
                new VmSchedulerTimeShared(peList));
        hostList.add(host);

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.001, 0.0);

        return new Datacenter(name, characteristics,
                new VmAllocationPolicySimple(hostList), new LinkedList<>(), 0.1);
    }

    private static DatacenterBroker createBroker() {
        try {
            return new DatacenterBroker("Broker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
