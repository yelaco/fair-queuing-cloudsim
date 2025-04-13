package org.advancedcloud.examples;

import org.advancedcloud.utils.CloudletDeadline;
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
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class OriginalTaskScheduling {
  private static final int HOSTS = 1;
  private static final int HOST_PES = 8;
  private static final int HOST_MIPS = 1000;
  private static final int HOST_RAM = 8192;
  private static final long HOST_BW = 40000;
  private static final long HOST_STORAGE = 1_000_000;
  private static final int VM_PES = 2;
  private static final int VMS = 3;
  private static final int CLOUDLET_PES = 1;
  private static int CLOUDLETS;

  private static final String INPUT_CSV = "tasks.csv";
  private static String OUTPUT_CSV;

  private final CloudSimPlus simulation;
  private final DatacenterBrokerSimple broker;
  private final List<Vm> vmList;
  private final Map<Long, Double> vmWait = new HashMap<>();
  private final Map<Long, Double> vmCost = new HashMap<>();

  private double offsetTime = 0;

  public static void main(String[] args) {
    int offset = args.length > 0 ? Integer.parseInt(args[0]) : 0;
    int totalLimit = args.length > 1 ? Integer.parseInt(args[1]) : 0;
    CLOUDLETS = args.length > 2 ? Integer.parseInt(args[2]) : VMS;
    OUTPUT_CSV = "results-original-" + String.valueOf(totalLimit) + "-" + String.valueOf(CLOUDLETS) + ".csv";
    new OriginalTaskScheduling(offset, totalLimit);
  }

  public OriginalTaskScheduling(int offset, int totalLimit) {
    offsetTime = readLastFinishTimeFromCSV();

    simulation = new CloudSimPlus();
    broker = new DatacenterBrokerSimple(simulation);

    createDatacenter();
    vmList = createVms();

    // Load batch
    List<CloudletDeadline> cloudlets = loadCloudletsFromCSV(INPUT_CSV, offset, totalLimit);

    // Classify
    List<CloudletDeadline> deadlineQueue = new ArrayList<>();
    List<CloudletDeadline> costQueue = new ArrayList<>();
    for (CloudletDeadline cl : cloudlets) {
      if (cl.getDeadline() <= 1.5 * cl.getLength())
        deadlineQueue.add(cl);
      else
        costQueue.add(cl);
    }

    // Weighted Fair Queuing
    deadlineQueue.sort(Comparator.comparingLong(CloudletDeadline::getDeadline));
    costQueue.sort(Comparator.comparingLong(Cloudlet::getLength));

    // Greedy Scheduling
    List<Cloudlet> scheduled = new ArrayList<>();
    scheduled.addAll(scheduleGreedy(deadlineQueue, true));
    scheduled.addAll(scheduleGreedy(costQueue, false));

    // Submit and simulate
    broker.submitCloudletList(scheduled);
    simulation.start();

    List<Cloudlet> finished = broker.getCloudletFinishedList();
    new CloudletsTableBuilder(finished).build();
    writeResultsToCSV(finished);
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
        return Double.parseDouble(parts[5]);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return 0.0;
  }

  private List<CloudletDeadline> loadCloudletsFromCSV(String filename, int offset, int totalLimit) {
    List<CloudletDeadline> cloudlets = new ArrayList<>();
    final var utilization = new UtilizationModelDynamic(0.5);
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
          CloudletDeadline cloudlet = new CloudletDeadline(length, CLOUDLET_PES, utilization, deadline);
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

  private List<Cloudlet> scheduleGreedy(List<CloudletDeadline> queue, boolean isDeadlineQueue) {
    List<Cloudlet> scheduled = new ArrayList<>();
    for (Cloudlet cl : queue) {
      Vm best = null;
      double bestMetric = Double.MAX_VALUE;
      for (Vm vm : vmList) {
        double wait = vmWait.get(vm.getId());
        double proc = vm.getMips();
        double metric;
        if (isDeadlineQueue) {
          metric = wait + (cl.getLength() / proc);
        } else {
          double cost = vmCost.get(vm.getId());
          metric = cost * (cl.getLength() / proc);
        }
        if (metric < bestMetric) {
          best = vm;
          bestMetric = metric;
        }
      }
      if (best != null) {
        cl.setVm(best);
        vmWait.put(best.getId(), vmWait.get(best.getId()) + (cl.getLength() / best.getMips()));
        scheduled.add(cl);
      }
    }
    return scheduled;
  }

  private void writeResultsToCSV(List<Cloudlet> list) {
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_CSV, true))) {
      for (Cloudlet cl : list) {
        CloudletDeadline cd = (CloudletDeadline) cl;
        double resourceCost = vmCost.get(cl.getVm().getId());
        double proc = cl.getVm().getMips();
        double taskCost = resourceCost * (cl.getLength() / proc);
        writer.write(String.format("%d,%d,%d,%d,%.5f,%.5f,%.5f,%.5f\n",
            cl.getId(), cl.getLength(), cd.getDeadline(), cl.getVm().getId(),
            cl.getStartTime() + offsetTime, cl.getFinishTime() + offsetTime,
            cl.getTotalExecutionTime(), taskCost));
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
        peList.add(new PeSimple(2 * HOST_MIPS));
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
      Vm vm = new VmSimple(HOST_MIPS + 100 * (VMS - i), VM_PES);
      vm.setRam(1024).setBw(5000).setSize(10000);
      vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
      vm.setId(i);
      list.add(vm);
      vmCost.put(vm.getId(), 0.01 + (VMS - i) * 0.01);
      vmWait.put(vm.getId(), 0.0);
    }
    broker.submitVmList(list);
    return list;
  }
}
