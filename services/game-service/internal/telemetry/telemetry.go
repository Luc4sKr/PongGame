package telemetry

import (
	"context"
	"fmt"
	"io"
	"log"
	"log/slog"
	"os"
	"time"

	"go.opentelemetry.io/contrib/bridges/otelslog"
	"go.opentelemetry.io/contrib/instrumentation/runtime"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploghttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/log/global"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/propagation"
	sdklog "go.opentelemetry.io/otel/sdk/log"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

const Scope = "ponggame/game"

func Init(ctx context.Context, instance string) (func(), error) {
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	if os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT") == "" {
		return func() {}, nil
	}
	res, err := resource.New(ctx, resource.WithFromEnv(), resource.WithTelemetrySDK(),
		resource.WithAttributes(attribute.String("service.instance.id", instance)))
	if err != nil {
		return nil, err
	}
	te, err := otlptracehttp.New(ctx)
	if err != nil {
		return nil, err
	}
	tp := sdktrace.NewTracerProvider(sdktrace.WithResource(res),
		sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.AlwaysSample())),
		sdktrace.WithBatcher(te, sdktrace.WithBatchTimeout(500*time.Millisecond)))
	otel.SetTracerProvider(tp)
	me, err := otlpmetrichttp.New(ctx)
	if err != nil {
		return nil, err
	}
	mp := sdkmetric.NewMeterProvider(sdkmetric.WithResource(res),
		sdkmetric.WithReader(sdkmetric.NewPeriodicReader(me, sdkmetric.WithInterval(5*time.Second))))
	otel.SetMeterProvider(mp)
	le, err := otlploghttp.New(ctx)
	if err != nil {
		return nil, err
	}
	lp := sdklog.NewLoggerProvider(sdklog.WithResource(res),
		sdklog.WithProcessor(sdklog.NewBatchProcessor(le, sdklog.WithExportInterval(200*time.Millisecond))))
	global.SetLoggerProvider(lp)
	log.SetOutput(io.MultiWriter(os.Stderr, logWriter{}))
	_ = runtime.Start(runtime.WithMinimumReadMemStatsInterval(5 * time.Second))
	return func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = tp.Shutdown(ctx)
		_ = mp.Shutdown(ctx)
		_ = lp.Shutdown(ctx)
	}, nil
}

type logWriter struct{}

func (logWriter) Write(p []byte) (int, error) {
	slog.New(otelslog.NewHandler(Scope)).Info(string(p))
	return len(p), nil
}

func Event(ctx context.Context, message string, args ...any) {
	slog.New(otelslog.NewHandler(Scope)).InfoContext(ctx, message, args...)
}

func Capture(ctx context.Context) map[string]string {
	carrier := propagation.MapCarrier{}
	otel.GetTextMapPropagator().Inject(ctx, carrier)
	return carrier
}

func Restore(ctx context.Context, carrier map[string]string) context.Context {
	return otel.GetTextMapPropagator().Extract(ctx, propagation.MapCarrier(carrier))
}

func Start(ctx context.Context, name string, attrs ...attribute.KeyValue) (context.Context, trace.Span) {
	return otel.Tracer(Scope).Start(ctx, name, trace.WithAttributes(attrs...))
}

func Error(span trace.Span, err error) {
	span.RecordError(err)
	span.SetStatus(codes.Error, err.Error())
}

func Count(ctx context.Context, name, outcome string) {
	counter, _ := otel.Meter(Scope).Int64Counter(name)
	counter.Add(ctx, 1, metric.WithAttributes(attribute.String("outcome", outcome)))
}

func DependencyDuration(ctx context.Context, seconds float64, failed bool) {
	h, _ := otel.Meter(Scope).Float64Histogram("pong.game.dependency.duration", metric.WithUnit("s"))
	h.Record(ctx, seconds, metric.WithAttributes(attribute.Bool("failed", failed)))
}

func Gauge(name string, read func(context.Context) (int64, error)) {
	_, err := otel.Meter(Scope).Int64ObservableGauge(name, metric.WithInt64Callback(
		func(ctx context.Context, observer metric.Int64Observer) error {
			value, err := read(ctx)
			if err == nil {
				observer.Observe(value)
			}
			return err
		}))
	if err != nil {
		log.Print(fmt.Errorf("register metric %s: %w", name, err))
	}
}
