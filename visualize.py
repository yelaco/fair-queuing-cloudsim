import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
from dash import Dash, dcc, html
import sys

# Input file
if len(sys.argv) > 1:
    file = sys.argv[1]
else:
    file = "results.csv"

# Load data
tasks = pd.read_csv("tasks.csv", header=None)
tasks.columns = ["task_id", "length", "deadline"]
df = pd.read_csv(file)

# Basic fields
df["duration"] = df["finish_time"] - df["start_time"]
df["thread_label"] = df["vm_id"].astype(str) + "-" + df["thread"].astype(str)
df["slack"] = df["deadline"] - df["length"]
df["urgency"] = df["slack"].apply(
    lambda x: "Tight" if x < 200 else "Medium" if x < 500 else "Loose"
)

# Summary statistics
task_count_vm = (
    df.groupby("vm_id").size().reset_index().rename(columns={0: "task_count"})
)
avg_duration = (
    df.groupby("vm_id")["duration"]
    .mean()
    .reset_index()
    .rename(columns={"duration": "avg_duration"})
)

# Parallelism chart
events = pd.concat(
    [
        df[["start_time"]].assign(event="start"),
        df[["finish_time"]]
        .assign(event="end")
        .rename(columns={"finish_time": "start_time"}),
    ]
)
events = events.sort_values("start_time").reset_index(drop=True)
events["active"] = (events["event"] == "start").cumsum() - (
    events["event"] == "end"
).cumsum()

# Thread utilization
heatmap_df = df.groupby(["vm_id", "thread"])["duration"].sum().reset_index()
heatmap_matrix = heatmap_df.pivot(
    index="vm_id", columns="thread", values="duration"
).fillna(0)

# Cumulative task completion
completed = df.sort_values(by="finish_time").copy()
completed["completed"] = range(1, len(completed) + 1)

# Thread Occupancy Matrix
df["time_bin"] = (df["start_time"] // 0.1).astype(int)
time_range = range(
    int(df["start_time"].min() // 0.1), int(df["finish_time"].max() // 0.1) + 1
)
thread_labels = pd.Index(sorted(df["thread_label"].unique()))
occupancy = pd.DataFrame(0, index=thread_labels, columns=time_range)

for _, row in df.iterrows():
    start_bin = int(row["start_time"] // 0.1)
    end_bin = int(row["finish_time"] // 0.1)
    occupancy.loc[row["thread_label"], start_bin:end_bin] = 1

occupancy_fig = px.imshow(
    occupancy,
    labels=dict(x="Time Bin (0.1 units)", y="VM-Thread", color="Occupied"),
    aspect="auto",
    color_continuous_scale="Blues",
    title="Thread Occupancy Timeline (per VM-Thread)",
)

# Initialize Dash app
app = Dash(__name__)
app.title = "2DFQ Scheduling Insights"

app.layout = html.Div(
    [
        html.H1("Dataset Insights", style={"textAlign": "center"}),
        dcc.Graph(
            figure=px.histogram(
                tasks,
                x="length",
                nbins=30,
                title="Distribution of Task Lengths",
                labels={"length": "Task Length (Million Instructions)"},
            )
        ),
        dcc.Graph(
            figure=px.histogram(
                tasks,
                x="deadline",
                nbins=30,
                title="Distribution of Task Deadlines",
                labels={"deadline": "Deadline (Time Units)"},
            )
        ),
        html.H1("Scheduling Insights", style={"textAlign": "center"}),
        dcc.Graph(
            figure=px.bar(
                task_count_vm,
                x="vm_id",
                y="task_count",
                title="Task Count per VM",
                text_auto=True,
                labels={"vm_id": "Logical VM ID", "task_count": "Tasks"},
            )
        ),
        dcc.Graph(
            figure=px.bar(
                avg_duration,
                x="vm_id",
                y="avg_duration",
                title="Average Task Duration per VM",
                text_auto=True,
                labels={"vm_id": "Logical VM ID", "avg_duration": "Avg Duration"},
            )
        ),
        dcc.Graph(
            figure=go.Figure(
                go.Scatter(
                    x=events["start_time"],
                    y=events["active"],
                    mode="lines",
                    fill="tozeroy",
                    name="Active Tasks",
                )
            ).update_layout(
                title="Parallelism Over Time",
                xaxis_title="Time",
                yaxis_title="Active Tasks",
            )
        ),
        dcc.Graph(
            figure=px.imshow(
                heatmap_matrix,
                title="Thread Utilization Heatmap",
                labels=dict(x="Thread", y="VM", color="Total Duration"),
                aspect="auto",
            )
        ),
        dcc.Graph(
            figure=px.scatter(
                df,
                x="slack",
                y="duration",
                color="urgency",
                title="Slack vs Execution Time",
                labels={
                    "slack": "Slack (Deadline - Length)",
                    "duration": "Execution Time",
                },
                hover_data=["task_id", "vm_id", "thread"],
            )
        ),
        dcc.Graph(figure=occupancy_fig),
        dcc.Graph(
            figure=px.line(
                completed,
                x="finish_time",
                y="completed",
                title="Cumulative Task Completion Over Time",
                labels={"finish_time": "Time", "completed": "Tasks Completed"},
            )
        ),
    ]
)

if __name__ == "__main__":
    app.run(debug=True)
