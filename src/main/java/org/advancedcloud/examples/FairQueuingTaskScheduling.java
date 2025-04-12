package org.advancedcloud.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.*;

public class FairQueuingTaskScheduling {

  private static final int HOSTS = 1;
  private static final int HOST_PES = 8;
  private static final int HOST_MIPS = 1000;
  private static final int HOST_RAM = 8192;
  private static final long HOST_BW = 40_000;
  private static final long HOST_STORAGE = 1_000_000;

  private static final int LOGICAL_VMS = 3;
  private static final int THREADS_PER_LOGICAL_VM = 2;
  private static final int VMS = LOGICAL_VMS * THREADS_PER_LOGICAL_VM;
  private static final int VM_PES = 1;
  private static final int CLOUDLET_PES = 1;
  private static final int BATCH_SIZE = VMS;
  private static final int TOTAL_CLOUDLETS = 12; // dynamically generated

  private final CloudSimPlus simulation;
  private final DatacenterBrokerSimple broker;
  private final List<Vm> vmList;
  private final List<Cloudlet> deadlineTasks = new ArrayList<>();
  private final List<Cloudlet> costTasks = new ArrayList<>();
  private final Map<Vm, Double> vmCost = new HashMap<>();
  private final Map<Vm, Double> vmWait = new HashMap<>();
  private final Map<Integer, Double> logicalVmVirtualTime = new HashMap<>();
  private static final int DEADLINE_THRESHOLD = 800;
  private static final int LENGTH_THRESHOLD = 800;

  public static void main(String[] args) {
    new FairQueuingTaskScheduling();
  }

  public FairQueuingTaskScheduling() {
    simulation = new CloudSimPlus();
    broker = new DatacenterBrokerSimple(simulation);

    createDatacenter();
    vmList = createVms();
    for (int i = 0; i < LOGICAL_VMS; i++) {
      logicalVmVirtualTime.put(i, 0.0);
    }

    List<Cloudlet> cloudlets = createCloudlets(TOTAL_CLOUDLETS);
    int index = 0;
    boolean started = false;
    while (index < cloudlets.size()) {
      List<Cloudlet> batch = cloudlets.subList(index, Math.min(index + BATCH_SIZE, cloudlets.size()));
      classifyTasks(batch);
      List<Cloudlet> toSubmit = new ArrayList<>();
      toSubmit.addAll(scheduleBatch(deadlineTasks, 4, true));
      toSubmit.addAll(scheduleBatch(costTasks, 4, false));
      broker.submitCloudletList(toSubmit);
      if (!started) {
        simulation.startSync();
        started = true;
      } else if (simulation.isRunning()) {
        simulation.runFor(1);
      }
      updateVirtualTimes(toSubmit);
      index += BATCH_SIZE;
    }
    simulation.runFor(5);
  }

  private void classifyTasks(List<Cloudlet> cloudlets) {
    deadlineTasks.clear();
    costTasks.clear();
    for (Cloudlet cl : cloudlets) {
      if (cl.getLength() <= DEADLINE_THRESHOLD)
        deadlineTasks.add(cl);
      else
        costTasks.add(cl);
    }
  }

  private List<Cloudlet> scheduleBatch(List<Cloudlet> queue, int batchSize, boolean isDeadlineQueue) {
    List<Cloudlet> selected = new ArrayList<>();

    for (int i = 0; i < batchSize && !queue.isEmpty(); i++) {
      Cloudlet cl = queue.remove(0);
      int bestLogicalVm = -1;
      double bestMetric = Double.MAX_VALUE;

      for (int lvm = 0; lvm < LOGICAL_VMS; lvm++) {
        Vm vm0 = vmList.get(lvm * 2);
        double wait = vmWait.getOrDefault(vm0, 0.0);
        double proc = vm0.getMips();

        if (isDeadlineQueue) {
          double turn = wait + (cl.getLength() / proc);
          if (turn < bestMetric) {
            bestLogicalVm = lvm;
            bestMetric = turn;
          }
        } else {
          double cost = vmCost.getOrDefault(vm0, 0.01) * (cl.getLength() / proc);
          if (cost < bestMetric) {
            bestLogicalVm = lvm;
            bestMetric = cost;
          }
        }
      }

      if (bestLogicalVm >= 0) {
        double virtualTime = logicalVmVirtualTime.getOrDefault(bestLogicalVm, 0.0);
        int assignedThread = -1;
        double bestEligible = Double.MAX_VALUE;

        boolean isSmall = cl.getLength() <= LENGTH_THRESHOLD;
        int start = isSmall ? THREADS_PER_LOGICAL_VM - 1 : 0;
        int end = isSmall ? -1 : THREADS_PER_LOGICAL_VM;
        int step = isSmall ? -1 : 1;

        for (int t = start; t != end; t += step) {
          Vm vm = vmList.get(bestLogicalVm * 2 + t);
          double wait = vmWait.getOrDefault(vm, 0.0);
          double proc = vm.getMips();
          double startTime = Math.max(wait, virtualTime);
          double eligible = startTime - (1 - ((double) (t + 1) / THREADS_PER_LOGICAL_VM)) * cl.getLength();

          if (eligible <= virtualTime) {
            assignedThread = t;
            break;
          }

          if (eligible < bestEligible) {
            assignedThread = t;
            bestEligible = eligible;
          }
        }

        Vm selectedVm = vmList.get(bestLogicalVm * 2 + assignedThread);
        cl.setVm(selectedVm);
        double newWait = vmWait.getOrDefault(selectedVm, 0.0) + (cl.getLength() / selectedVm.getMips());
        vmWait.put(selectedVm, newWait);
        selected.add(cl);
      }
    }

    return selected;
  }

  private void updateVirtualTimes(List<Cloudlet> cloudlets) {
    Map<Integer, Double> accLength = new HashMap<>();

    for (Cloudlet cl : cloudlets) {
      long logicalVmId = cl.getVm().getId() / THREADS_PER_LOGICAL_VM;
      accLength.put((int) logicalVmId, accLength.getOrDefault((int) logicalVmId, 0.0) + cl.getLength());
    }

    for (Map.Entry<Integer, Double> entry : accLength.entrySet()) {
      int logicalVmId = entry.getKey();
      double update = entry.getValue() / THREADS_PER_LOGICAL_VM;
      double prev = logicalVmVirtualTime.getOrDefault(logicalVmId, 0.0);
      double newVt = prev + update;
      logicalVmVirtualTime.put(logicalVmId, newVt);
    }
  }

  private Datacenter createDatacenter() {
    List<Host> hostList = new ArrayList<>();

    for (int i = 0; i < HOSTS; i++) {
      List<Pe> peList = new ArrayList<>();
      for (int j = 0; j < HOST_PES; j++) {
        peList.add(new PeSimple(HOST_MIPS));
      }
      Host host = new HostSimple(HOST_RAM, HOST_BW, HOST_STORAGE, peList);
      host.setVmScheduler(new VmSchedulerSpaceShared());
      hostList.add(host);
    }

    return new DatacenterSimple(simulation, hostList);
  }

  private List<Vm> createVms() {
    List<Vm> list = new ArrayList<>();
    for (int i = 0; i < VMS; i++) {
      Vm vm = new VmSimple(HOST_MIPS, VM_PES);
      vm.setRam(1024).setBw(5_000).setSize(10_000);
      vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
      list.add(vm);
      vmCost.put(vm, 0.01 + i * 0.01);
      vmWait.put(vm, 0.0);
    }
    broker.submitVmList(list);
    return list;
  }

  private List<Cloudlet> createCloudlets(int count) {
    List<Cloudlet> cloudlets = new ArrayList<>();
    final var utilizationModel = new UtilizationModelFull();
    Random random = new Random();
    long baseLength = 500;

    for (int i = 0; i < count; i++) {
      long length = baseLength + random.nextInt(1200);
      Cloudlet cloudlet = new CloudletSimple(length, CLOUDLET_PES, utilizationModel);
      cloudlet.setId(i);
      cloudlet.setSizes(1024);
      cloudlets.add(cloudlet);
    }
    return cloudlets;
  }
}
