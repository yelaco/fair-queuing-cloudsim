### Detailed Explanation of the Papers

#### **1. Dynamic Fair Priority Optimization Task Scheduling in Cloud Computing**

- **Objective**: Optimize task scheduling in cloud environments to balance efficiency and fairness for users and providers.
- **Key Concepts**:
  - **Constraint-based Grouping**: Tasks are classified into deadline-based and cost-based groups to address user (QoS) and provider (profit) constraints.
  - **Weighted Fair Queuing (WFQ)**: Tasks are assigned to high, mid, and low priority queues. Each queue is serviced in a weighted round-robin fashion (e.g., 3 tasks from high, 2 from mid, 1 from low per cycle).
  - **Dynamic Optimization**:
    - For deadline-based tasks: Select VMs with minimum turnaround time.
    - For cost-based tasks: Assign to VMs with minimum execution cost.
  - **Avoids Starvation**: Ensures low-priority tasks are not indefinitely delayed by using round-robin within priority tiers.
- **Implementation**:
  - Simulated using CloudSim, showing reduced execution time and cost compared to sequential and static algorithms.

#### **2. 2DFQ: Two-Dimensional Fair Queuing for Multi-Tenant Cloud Services**

- **Objective**: Provide smooth, fair scheduling in multi-tenant systems with high concurrency and variable request costs.
- **Key Concepts**:
  - **Two-Dimensional Scheduling**:
    - **Time**: Orders requests by virtual start/finish times (like WFQ).
    - **Thread Allocation**: Spreads requests across threads based on cost (small requests on high-index threads, large on low-index threads).
  - **Handling Unknown Costs**:
    - **Pessimistic Estimation**: Overestimates costs to isolate unpredictable tenants.
    - **Retroactive Charging**: Adjusts fairness counters post-execution to account for actual resource usage.
    - **Refresh Charging**: Periodically updates cost estimates during long-running tasks.
  - **Reduces Burstiness**: Prevents large requests from blocking small ones by segregating them across threads.
- **Results**:
  - Reduces 99th percentile latency by up to 100x for predictable tenants competing with unpredictable workloads.

---

### Combining the Ideas and Theories

#### **1. Integrate Dynamic Priority Optimization into 2DFQ**

- **Step 1**: Use the first paper’s **constraint-based grouping** (deadline/cost) to classify tasks.
- **Step 2**: Apply **2DFQ’s thread allocation** to each group:
  - High-priority (deadline-sensitive) tasks → Assign to threads optimized for low latency (high-index threads).
  - Cost-based tasks → Assign to threads optimized for resource efficiency (low-index threads).
- **Benefit**: Combines user-level QoS (deadlines) and provider-level efficiency (cost) with 2DFQ’s burst-reduction.

#### **2. Enhance 2DFQ’s Cost Estimation with Dynamic Weights**

- **Dynamic Weight Adjustment**: Use the first paper’s **dynamic optimization** to adjust queue weights in real-time based on:
  - System load (e.g., increase high-priority weight during peak demand).
  - Historical data (e.g., shift resources to cost-based tasks if VM utilization drops).
- **Pessimistic Estimation for Priorities**: Apply 2DFQ’s pessimistic cost estimation to prioritize critical tasks (e.g., deadline-based) by overestimating their resource needs, ensuring they occupy dedicated threads.

#### **3. Hybrid Scheduling Model**

- **Layer 1 (Grouping)**: Classify tasks into deadline/cost groups (first paper).
- **Layer 2 (Scheduling)**:
  - For deadline groups: Use 2DFQ’s thread allocation to ensure small, urgent tasks avoid blocking.
  - For cost groups: Use greedy VM allocation (first paper) on low-index threads to maximize provider profit.
- **Layer 3 (Adaptation)**: Continuously update weights and cost estimates using retroactive/refresh charging (2DFQ) and dynamic optimization (first paper).

#### **4. Practical Implementation**

- **In CloudSim**:
  1. Modify the Data Center Broker to support 2DFQ’s thread allocation logic.
  2. Integrate dynamic grouping and priority queues into the scheduler.
  3. Add pessimistic cost estimation and retroactive charging for VM selection.
- **Metrics to Track**:
  - Fairness (Gini index, service lag).
  - Efficiency (VM utilization, task completion time).
  - Cost/Deadline Compliance (QoS adherence).

---

### Challenges and Solutions

1. **Complexity**: Combining two sophisticated algorithms may increase overhead.
   - **Solution**: Optimize thread allocation logic and use lightweight dynamic adjustments.
2. **Conflicting Goals**: Deadline-driven vs. cost-driven tasks might compete for resources.
   - **Solution**: Use adaptive weights to balance priorities based on real-time demand.
3. **Unpredictable Workloads**: Sudden spikes in task size/cost.
   - **Solution**: Leverage 2DFQ’s pessimistic estimation to isolate unpredictable tasks.

---

### Summary

By merging the **dynamic, constraint-aware scheduling** of the first paper with **2DFQ’s burst-resistant, multi-threaded fairness**, you create a hybrid model that:

- Balances user QoS (deadlines) and provider efficiency (cost).
- Reduces latency for small tasks while handling large/unpredictable requests.
- Adapts to changing workloads through dynamic optimization and pessimistic estimation.

This combination addresses both **micro-level task priorities** and **macro-level system concurrency**, offering a robust solution for modern cloud environments.
To implement the combined approach of **Dynamic Fair Priority Optimization** (from the second paper) into **2DFQ** (from the first paper), follow these steps:

---

### **1. Task Classification and Grouping**

- **Step 1.1**: Classify incoming tasks into **deadline-based** (user QoS) or **cost-based** (provider profit) groups.
- **Step 1.2**: Further subdivide each group into **priority tiers** (high, mid, low) using dynamic weights:
  - For deadline-based tasks: Prioritize by urgency (e.g., shorter deadlines → higher priority).
  - For cost-based tasks: Prioritize by task length (longer tasks → higher priority for cost efficiency).

---

### **2. Integrate Weighted Fair Queuing (WFQ) with 2DFQ**

- **Step 2.1**: For each priority tier (high/mid/low), maintain separate queues.
- **Step 2.2**: Apply **2DFQ’s thread allocation** within each tier:
  - Small tasks → Assign to **high-index threads** (optimized for latency).
  - Large tasks → Assign to **low-index threads** (isolated to prevent blocking).
    - **Step 2.3**: Use **weighted round-robin** across priority tiers:
  - Example weights: High:3, Mid:2, Low:1 (process 3 high-priority tasks, then 2 mid, then 1 low per cycle).

---

### **3. VM Allocation with Dynamic Optimization**

- **Step 3.1**: For **deadline-based tasks**:
  - Use **greedy VM selection** based on minimum turnaround time:
    ```python
    turnaround_time = VM_wait_time + (task_length / VM_MIPS)
    Select VM with smallest turnaround_time.
    ```
- **Step 3.2**: For **cost-based tasks**:
  - Use **greedy VM selection** based on minimum execution cost:
    ```python
    cost = VM_cost_per_sec * (task_length / VM_MIPS)
    Select VM with smallest cost.
    ```

---

### **4. Handling Unknown Costs (2DFQ\* Extension)**

- **Step 4.1**: Apply **pessimistic cost estimation**:
  - Track `L_max` (max observed cost) per tenant/API.
  - Overestimate costs for unpredictable tenants to isolate them on low-index threads.
- **Step 4.2**: Implement **retroactive charging**:
  - Adjust fairness counters post-execution using actual task costs.
- **Step 4.3**: Use **refresh charging** for long-running tasks:
  - Periodically update cost estimates (e.g., every 10ms) to mitigate feedback delays.

---

### **5. Adaptive Thread Allocation**

- **Step 5.1**: Dynamically adjust thread allocation weights based on system load:
  - Example: Increase high-priority thread allocation during peak demand.
- **Step 5.2**: Use **2DFQ’s virtual time** to ensure fairness:
  - Compute virtual start/finish times for tasks:
    ```python
    S(r_j) = max(virtual_time, F(r_{j-1}))
    F(r_j) = S(r_j) + (task_cost / tenant_weight)
    ```
  - Schedule tasks with the smallest `F(r_j)` first.

---

### **6. Implementation Workflow**

```python
def combined_scheduler(tasks, vms):
    # Step 1: Classify tasks into deadline/cost groups
    deadline_tasks, cost_tasks = classify_tasks(tasks)

    # Step 2: Subdivide into priority tiers
    deadline_queues = prioritize(deadline_tasks, by="deadline")
    cost_queues = prioritize(cost_tasks, by="length")

    # Step 3: Process each priority tier with 2DFQ
    for queue in [deadline_queues.high, deadline_queues.mid, deadline_queues.low]:
        for task in queue:
            # Apply 2DFQ thread allocation
            thread = assign_thread(task.size)
            # Assign VM based on group
            vm = select_vm(task, vms, mode="deadline")
            execute(task, vm, thread)

    for queue in [cost_queues.high, cost_queues.mid, cost_queues.low]:
        for task in queue:
            thread = assign_thread(task.size)
            vm = select_vm(task, vms, mode="cost")
            execute(task, vm, thread)

    # Step 4: Handle unknown costs (2DFQ*)
    update_cost_estimates()
    apply_retroactive_charges()
```

---

### **7. Key Components to Modify in 2DFQ**

1. **Task Queues**:
   - Replace per-tenant queues with **priority-tiered queues** (high/mid/low) for deadline/cost groups.
2. **Thread Allocation**:
   - Map small tasks to high-index threads, large to low-index, but within priority tiers.
3. **VM Selection**:
   - Integrate greedy VM selection logic for deadline/cost groups.
4. **Cost Estimation**:
   - Add modules for pessimistic estimation and retroactive charging.

---

### **8. Metrics to Evaluate**

- **Fairness**: Gini index, service lag variation.
- **Efficiency**: Total execution time, VM utilization.
- **QoS Compliance**: Deadline misses, cost overruns.
- **Burstiness**: 99th percentile latency for small tasks.

---

### **Challenges & Solutions**

- **Complexity**: Use modular design to separate grouping, prioritization, and scheduling.
- **Starvation**: Ensure low-priority tasks are served via weighted round-robin.
- **Overhead**: Optimize cost estimation with incremental updates.

---

### **Summary**

By integrating **dynamic priority tiers**, **weighted fair queuing**, and **2DFQ’s thread allocation**, you create a hybrid scheduler that:

- Balances user QoS and provider profit.
- Minimizes latency for small tasks while isolating large/unpredictable ones.
- Adapts to workload changes through dynamic optimization and pessimistic cost estimation.

This approach leverages the strengths of both papers, making it ideal for multi-tenant cloud systems with diverse workloads.
# fair-queuing-cloudsim
