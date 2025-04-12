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

# Deadline slack distribution
slack_hist = px.histogram(
    df,
    x="slack",
    color="urgency",
    nbins=30,
    title="Slack Time Distribution (Deadline - Task Length)",
    labels={"slack": "Slack Time", "count": "Task Count"},
)

# Initialize Dash app
app = Dash(__name__)
app.title = "2DFQ Scheduling Insights"

app.layout = html.Div(
    [
        html.H1("2DFQ Scheduling Insights", style={"textAlign": "center"}),
        dcc.Graph(
            figure=px.timeline(
                df,
                x_start="start_time",
                x_end="finish_time",
                y="task_id",
                color="thread_label",
                title="Gantt Chart: Task Execution Timeline",
                labels={"thread_label": "VM-Thread"},
            ).update_yaxes(autorange="reversed")
        ),
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
        dcc.Graph(figure=slack_hist),
    ]
)

if __name__ == "__main__":
    app.run(debug=True)
