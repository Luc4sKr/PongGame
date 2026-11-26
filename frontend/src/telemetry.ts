import { SpanKind, SpanStatusCode, trace } from '@opentelemetry/api';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { TracerProvider, BatchSpanProcessor } from '@opentelemetry/sdk-trace';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';

const provider = new TracerProvider({
  resource: resourceFromAttributes({
    'service.name': 'ponggame-frontend',
    'service.namespace': 'ponggame',
    'deployment.environment.name': 'local',
  }),
  spanProcessors: [new BatchSpanProcessor({
    exporter: new OTLPTraceExporter({ url: '/telemetry/v1/traces' }),
    scheduledDelayMillis: 1000,
  })],
});
trace.setGlobalTracerProvider(provider);
const tracer = trace.getTracer('ponggame/frontend');

// Instrument only application requests. Exporter requests cannot recursively
// instrument themselves, and bodies/Authorization/query tokens are never captured.
export async function tracedFetch(path: string, init?: RequestInit): Promise<Response> {
  const route = path.split('?')[0].replace(/[0-9a-f]{8}-[0-9a-f-]{27,}/gi, '{id}');
  const method = init?.method ?? 'GET';
  const span = tracer.startSpan(`${method} ${route}`, {
    kind: SpanKind.CLIENT,
    attributes: { 'http.request.method': method, 'http.route': route, 'server.address': window.location.hostname },
  });
  const headers = new Headers(init?.headers);
  const sc = span.spanContext();
  headers.set('traceparent', `00-${sc.traceId}-${sc.spanId}-${sc.traceFlags.toString(16).padStart(2, '0')}`);
  try {
    const response = await fetch(path, { ...init, headers });
    span.setAttribute('http.response.status_code', response.status);
    if (response.status >= 500) span.setStatus({ code: SpanStatusCode.ERROR, message: `HTTP ${response.status}` });
    return response;
  } catch (error) {
    span.setStatus({ code: SpanStatusCode.ERROR, message: 'Network request failed' });
    span.recordException({ name: 'NetworkError', message: 'Network request failed' });
    throw error;
  } finally {
    span.end();
  }
}
