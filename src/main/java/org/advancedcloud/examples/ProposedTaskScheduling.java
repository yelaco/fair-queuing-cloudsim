package org.advancedcloud.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
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
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.advancedcloud.utils.CloudletDeadline;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ProposedTaskScheduling {

  private static final int LOGICAL_VMS = 3;
  private static final int THREADS_PER_LOGICAL_VM = 2;
  private static final int VMS = LOGICAL_VMS * THREADS_PER_LOGICAL_VM;
  private static final int VM_PES = 1;
  private static final int CLOUDLET_PES = 1;

  private static final int HOSTS = 1;
  private static final int HOST_PES = 8;
  private static final int HOST_MIPS = 1_000;
  private static final int HOST_RAM = 8192;
  private static final long HOST_BW = 40_000;
  private static final long HOST_STORAGE = 1_000_000;

  private static int CLOUDLETS;

  private static final int LENGTH_THRESHOLD = 500;

  private static final String INPUT_CSV = "tasks.csv";
  private static String OUTPUT_CSV;
  private static final String VIRTUAL_TIME_CSV = "virtual_times.csv";

  private final CloudSimPlus simulation;
  private final DatacenterBrokerSimple broker;
  private final List<Vm> vmList;
  private final List<Cloudlet> deadlineTasks = new ArrayList<>();
  private final List<Cloudlet> costTasks = new ArrayList<>();
  private final Map<Long, Double> vmCost = new HashMap<>();
  private final Map<Long, Double> vmWait = new HashMap<>();
  private final Map<Integer, Double> logicalVmVirtualTime = new HashMap<>();

  private double offsetTime;

  public static void main(String[] args) {
    int offset = args.length > 0 ? Integer.parseInt(args[0]) : 0;
    int totalLimit = args.length > 1 ? Integer.parseInt(args[1]) : 0;
    CLOUDLETS = args.length > 2 ? Integer.parseInt(args[2]) : VMS;
    OUTPUT_CSV = "results-proposed-" + String.valueOf(totalLimit) + "-" + String.valueOf(CLOUDLETS) + ".csv";
    new ProposedTaskScheduling(offset, totalLimit);
  }

  public ProposedTaskScheduling(int offset, int totalLimit) {
    offsetTime = readLastFinishTimeFromCSV();

    simulation = new CloudSimPlus();
    broker = new DatacenterBrokerSimple(simulation);

    createDatacenter();
    vmList = createVms();
    initializeVirtualTimes();

    List<Cloudlet> cloudlets = loadCloudletsFromCSV(INPUT_CSV, offset, totalLimit);
    classifyTasks(cloudlets);
    System.out.println(deadlineTasks.toString());
    System.out.println(costTasks.toString());

    List<Cloudlet> batch = new ArrayList<>();
    batch.addAll(scheduleBatch(deadlineTasks, true));
    batch.addAll(scheduleBatch(costTasks, false));
    broker.submitCloudletList(batch);

    simulation.start();

    List<Cloudlet> finished = broker.getCloudletFinishedList();
    new CloudletsTableBuilder(finished).build();
    writeResultsToCSV(finished);
    updateVirtualTimes(finished);
  }

  private double readLastFinishTimeFromCSV() {
    File file = new File(OUTPUT_CSV);
    if (!file.exists())
      return 0.0;

    String lastLine = "";
    try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null)
        lastLine = line;
      if (!lastLine.isEmpty()) {
        String[] parts = lastLine.split(",");
        if (parts.length >= 8)
          return Double.parseDouble(parts[6]);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return 0.0;
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
      if (((CloudletDeadline) cl).getDeadline() <= 1.5 * cl.getLength())
        deadlineTasks.add(cl);
      else
        costTasks.add(cl);
    }
  }

  private List<Cloudlet> scheduleBatch(List<Cloudlet> queue, boolean isDeadlineQueue) {
    List<Cloudlet> selected = new ArrayList<>();

    while (!queue.isEmpty()) {
      Cloudlet cl = queue.remove(0);
      int bestLogicalVm = -1;
      double bestMetric = Double.MAX_VALUE;

      for (int lvm = 0; lvm < LOGICAL_VMS; lvm++) {
        Vm vm0 = vmList.get(lvm * 2);
        Vm vm1 = vmList.get(lvm * 2 + 1);
        double waitTime = vmWait.get(vm0.getId()) + vmWait.get(vm1.getId()) / 2;
        double proc = vm0.getMips() + vm1.getMips();
        double resourceCost = vmCost.get(vm0.getId()) + vmCost.get(vm1.getId());

        if (isDeadlineQueue) {
          double turnaroundTime = waitTime + (cl.getLength() / proc);
          System.out.println(cl.getLength());
          System.out.println(turnaroundTime);
          if (turnaroundTime < bestMetric) {
            bestLogicalVm = lvm;
            bestMetric = turnaroundTime;
          }
        } else {
          double cost = resourceCost * cl.getLength() / proc;
          if (cost < bestMetric && cl.getLength() < proc) {
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
        vmWait.put(selectedVm.getId(), newWait);
        selected.add(cl);
      }
    }

    return selected;
  }

  private void updateVirtualTimes(List<Cloudlet> cloudlets) {
    Map<Integer, Double> accLength = new HashMap<>();

    for (Cloudlet cl : cloudlets) {
      int logicalVmId = (int) (cl.getVm().getId() / THREADS_PER_LOGICAL_VM);
      accLength.put(logicalVmId, accLength.getOrDefault(logicalVmId, 0.0) + cl.getLength());
    }

    try (BufferedWriter writer = new BufferedWriter(new FileWriter(VIRTUAL_TIME_CSV, false))) {
      for (int i = 0; i < LOGICAL_VMS; i++) {
        double prev = logicalVmVirtualTime.getOrDefault(i, 0.0);
        double update = accLength.getOrDefault(i, 0.0) / THREADS_PER_LOGICAL_VM;
        double newVt = prev + update;
        logicalVmVirtualTime.put(i, newVt);
        writer.write(String.format("%d,%.2f\n", i, newVt));
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
        peList.add(new PeSimple(HOST_MIPS * 2));
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
      Vm vm = new VmSimple(HOST_MIPS + 100 * (LOGICAL_VMS - (i / THREADS_PER_LOGICAL_VM)), VM_PES);
      vm.setRam(1024).setBw(5_000).setSize(10_000);
      vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
      vm.setId(i);
      list.add(vm);
      vmCost.put(vm.getId(), 0.01 + (LOGICAL_VMS - (i / THREADS_PER_LOGICAL_VM)) * 0.01);
      vmWait.put(vm.getId(), 0.0);
    }
    broker.submitVmList(list);
    return list;
  }

  private List<Cloudlet> loadCloudletsFromCSV(String filename, int offset, int totalLimit) {
    List<Cloudlet> cloudlets = new ArrayList<>();
    final var utilizationModel = new UtilizationModelFull();
    int maxTasksToLoad = Math.min(CLOUDLETS, totalLimit - offset); // don't load more than remaining allowed

    try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
      String line;
      int lineIndex = 0, count = 0;

      while ((line = br.readLine()) != null) {
        if (lineIndex++ < offset)
          continue;
        if (count++ >= maxTasksToLoad)
          break;

        String[] parts = line.split(",");
        if (parts.length >= 3) {
          int taskId = Integer.parseInt(parts[0]);
          long length = Long.parseLong(parts[1]);
          long deadline = Long.parseLong(parts[2]);
          Cloudlet cloudlet = new CloudletDeadline(length, CLOUDLET_PES, utilizationModel, deadline);
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
        double resourceCost = vmCost.get(cl.getVm().getId());
        double proc = cl.getVm().getMips();
        double taskCost = resourceCost * (cl.getLength() / proc);

        writer.write(String.format("%d,%d,%d,%d,%d,%.5f,%.5f,%.5f,%.5f\n",
            cl.getId(), cl.getLength(), ((CloudletDeadline) cl).getDeadline(),
            cl.getVm().getId() / THREADS_PER_LOGICAL_VM, cl.getVm().getId() % 2, cl.getStartTime() + offsetTime,
            cl.getFinishTime() + offsetTime, cl.getTotalExecutionTime(), taskCost));
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
