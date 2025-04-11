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
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.io.*;
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
  private static final int CLOUDLETS = VMS;

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

  private static final String INPUT_CSV = "tasks.csv";
  private static final String OUTPUT_CSV = "results.csv";
  private static final String VIRTUAL_TIME_CSV = "virtual_times.csv";

  public static void main(String[] args) {
    int offset = args.length > 0 ? Integer.parseInt(args[0]) : 0;
    new FairQueuingTaskScheduling(offset);
  }

  public FairQueuingTaskScheduling(int offset) {
    simulation = new CloudSimPlus();
    broker = new DatacenterBrokerSimple(simulation);

    createDatacenter();
    vmList = createVms();
    initializeVirtualTimes();

    List<Cloudlet> cloudlets = loadCloudletsFromCSV(INPUT_CSV, offset);
    classifyTasks(cloudlets);

    List<Cloudlet> batch = new ArrayList<>();
    batch.addAll(scheduleBatch(deadlineTasks, 4, true));
    batch.addAll(scheduleBatch(costTasks, 4, false));
    broker.submitCloudletList(batch);

    simulation.start();

    final var cloudletFinishedList = broker.getCloudletFinishedList();
    new CloudletsTableBuilder(cloudletFinishedList).build();
    writeResultsToCSV(cloudletFinishedList);
    updateVirtualTimes(cloudletFinishedList);
  }

  private void initializeVirtualTimes() {
    File file = new File(VIRTUAL_TIME_CSV);
    if (!file.exists()) {
      for (int i = 0; i < LOGICAL_VMS; i++) {
        logicalVmVirtualTime.put(i, 0.0);
      }
      return;
    }

    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split(",");
        if (parts.length >= 2) {
          int vmId = Integer.parseInt(parts[0]);
          double vt = Double.parseDouble(parts[1]);
          logicalVmVirtualTime.put(vmId, vt);
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void classifyTasks(List<Cloudlet> cloudlets) {
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

    try (BufferedWriter writer = new BufferedWriter(new FileWriter(VIRTUAL_TIME_CSV, false))) {
      for (Map.Entry<Integer, Double> entry : accLength.entrySet()) {
        int logicalVmId = entry.getKey();
        double update = entry.getValue() / THREADS_PER_LOGICAL_VM;
        double prev = logicalVmVirtualTime.getOrDefault(logicalVmId, 0.0);
        double newVt = prev + update;
        logicalVmVirtualTime.put(logicalVmId, newVt);
        writer.write(String.format("%d,%.2f\n", logicalVmId, newVt));
      }
    } catch (IOException e) {
      e.printStackTrace();
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

  private List<Cloudlet> loadCloudletsFromCSV(String filename, int offset) {
    List<Cloudlet> cloudlets = new ArrayList<>();
    final var utilizationModel = new UtilizationModelFull();
    try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
      String line;
      int lineIndex = 0, count = 0;
      while ((line = br.readLine()) != null) {
        if (lineIndex++ < offset)
          continue;
        if (count++ >= CLOUDLETS)
          break;
        String[] parts = line.split(",");
        if (parts.length >= 2) {
          int taskId = Integer.parseInt(parts[0]);
          long length = Long.parseLong(parts[1]);
          Cloudlet cloudlet = new CloudletSimple(length, CLOUDLET_PES, utilizationModel);
          cloudlet.setId(taskId);
          cloudlet.setSizes(1024);
          cloudlets.add(cloudlet);
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return cloudlets;
  }

  private void writeResultsToCSV(List<Cloudlet> cloudletList) {
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_CSV, true))) {
      for (Cloudlet cl : cloudletList) {
        writer.write(String.format("%d,%d,%d,%.2f,%.2f\n",
            cl.getId(), cl.getLength(), cl.getVm().getId() / THREADS_PER_LOGICAL_VM,
            cl.getTotalExecutionTime(), cl.getFinishTime()));
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
