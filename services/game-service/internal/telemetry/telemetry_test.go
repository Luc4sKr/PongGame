package telemetry

import (
	"context"
	"encoding/json"
	"testing"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

func TestTraceSurvivesOutboxSerialization(t *testing.T) {
	previous := otel.GetTextMapPropagator()
	otel.SetTextMapPropagator(propagation.TraceContext{})
	t.Cleanup(func() { otel.SetTextMapPropagator(previous) })
	sc := trace.NewSpanContext(trace.SpanContextConfig{TraceID: trace.TraceID{1}, SpanID: trace.SpanID{2}, TraceFlags: trace.FlagsSampled})
	ctx := trace.ContextWithSpanContext(context.Background(), sc)
	raw, err := json.Marshal(Capture(ctx))
	if err != nil {
		t.Fatal(err)
	}
	var persisted map[string]string
	if err := json.Unmarshal(raw, &persisted); err != nil {
		t.Fatal(err)
	}
	restored := trace.SpanContextFromContext(Restore(context.Background(), persisted))
	if restored.TraceID() != sc.TraceID() || restored.SpanID() != sc.SpanID() || !restored.IsSampled() || !restored.IsRemote() {
		t.Fatalf("outbox lost W3C context: %v", restored)
	}
}

func TestLegacyOutboxWithoutContext(t *testing.T) {
	ctx := Restore(context.Background(), nil)
	if trace.SpanContextFromContext(ctx).IsValid() {
		t.Fatal("legacy record must not invent a parent trace")
	}
}
