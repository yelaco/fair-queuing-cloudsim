import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
from dash import Dash, dcc, html

# Load Data
df = pd.read_csv("results.csv")

# Debug: Print data info
print("Data loaded. First 5 rows:")
print(df.head())
print("\nColumn types:")
print(df.dtypes)

# Compute additional fields
df["duration"] = df["finish_time"] - df["start_time"]
df["thread_label"] = df["vm_id"].astype(str) + "-" + df["thread"].astype(str)

# Ensure numeric timestamps
df["start_time"] = pd.to_numeric(df["start_time"])
df["finish_time"] = pd.to_numeric(df["finish_time"])

# Create Gantt chart with explicit checks
try:
    gantt_fig = px.timeline(
        df,
        x_start="start_time",
        x_end="finish_time",
        y="task_id",
        color="thread_label",
        title="Gantt Chart: Task Execution Timeline",
        labels={"thread_label": "VM-Thread"},
    )
    gantt_fig.update_yaxes(autorange="reversed")
    print("Gantt chart created successfully")
except Exception as e:
    print(f"Error creating Gantt chart: {e}")
    gantt_fig = go.Figure()
    gantt_fig.update_layout(title="Gantt Chart: Data Error")

# Rest of your code remains the same...
app = Dash(__name__)
app.title = "Scheduling Analytics Dashboard"

app.layout = html.Div(
    [
        html.H1("2DFQ Task Scheduling Analytics", style={"textAlign": "center"}),
        dcc.Graph(figure=gantt_fig),
        # ... rest of your layout
    ]
)

if __name__ == "__main__":
    app.run(debug=True)
