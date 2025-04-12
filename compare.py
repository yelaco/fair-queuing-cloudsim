import pandas as pd
import matplotlib.pyplot as plt
from pathlib import Path

# ==== CONFIGURATION ====
sizes = [200, 400, 600, 800, 1000]
algorithms = ["sequential", "original", "proposed"]
base_dir = "./results"  # Change this if your CSVs are stored elsewhere


# ==== LOAD DATA ====
def load_results(sizes, algorithms, base_dir):
    results = []
    for size in sizes:
        for algo in algorithms:
            path = Path(base_dir) / f"{algo}/results-{algo}-{size}-10.csv"
            if path.exists():
                df = pd.read_csv(path)
                df["algorithm"] = algo.capitalize()
                df["size"] = size
                results.append(df)
    return pd.concat(results, ignore_index=True)


df = load_results(sizes, algorithms, base_dir)
df = df.dropna(subset=["cost"])

# ==== AGGREGATE ====
summary = (
    df.groupby(["size", "algorithm"])
    .agg(total_cost=("cost", "sum"), avg_exec_time=("exec_time", "mean"))
    .reset_index()
)

pivot_time = summary.pivot(index="size", columns="algorithm", values="avg_exec_time")
pivot_cost = summary.pivot(index="size", columns="algorithm", values="total_cost")

# ==== PLOT ====
colors = ["#3366cc", "#dc3912", "#109618"]  # sequential, original, proposed

fig, axs = plt.subplots(2, 1, figsize=(10, 10))

# Task Completion Time
pivot_time.plot(kind="bar", ax=axs[0], color=colors)
axs[0].set_title("Task Completion Time Comparison")
axs[0].set_ylabel("Average Execution Time")
axs[0].set_xlabel("Number of Tasks")
axs[0].legend(title="Algorithm")

# Cost Comparison
pivot_cost.plot(kind="bar", ax=axs[1], color=colors)
axs[1].set_title("Cost Comparison")
axs[1].set_ylabel("Total Cost")
axs[1].set_xlabel("Number of Tasks")
axs[1].legend(title="Algorithm")

plt.tight_layout()
plt.savefig("results/comparison_result.png", dpi=300)
plt.show()
