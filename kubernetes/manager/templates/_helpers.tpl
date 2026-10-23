{{/*
Expand the name of the chart.
*/}}
{{- define "manager.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "manager.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "manager.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "manager.labels" -}}
helm.sh/chart: {{ include "manager.chart" . }}
{{ include "manager.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "manager.selectorLabels" -}}
app.kubernetes.io/name: {{ include "manager.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "manager.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "manager.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Return the PVC used for manager data.
*/}}
{{- define "manager.persistence.claimName" -}}
{{- default (include "manager.fullname" .) .Values.persistence.existingClaim -}}
{{- end }}

{{/*
Create the name of the mqtt service to use
*/}}
{{- define "manager.mqttServiceName" -}}
{{- printf "%s-%s" (include "manager.fullname" .) "mqtt" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "manager.mqttService.labels" -}}
helm.sh/chart: {{ include "manager.chart" . }}
app.kubernetes.io/name: {{ include "manager.mqttServiceName" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "manager.mqttsServiceName" -}}
{{- printf "%s-%s" (include "manager.fullname" .) "mqtts" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "manager.mqttsService.labels" -}}
helm.sh/chart: {{ include "manager.chart" . }}
app.kubernetes.io/name: {{ include "manager.mqttsServiceName" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Validate logging values.
*/}}
{{- define "manager.logging.validate" -}}
{{- if and .Values.logging.config .Values.logging.existingConfigMap -}}
{{- fail "manager chart values logging.config and logging.existingConfigMap are mutually exclusive" -}}
{{- end -}}
{{- if and (not .Values.logging.existingConfigMap) (ne (default "logging.properties" .Values.logging.existingConfigMapKey) "logging.properties") -}}
{{- fail "manager chart value logging.existingConfigMapKey requires logging.existingConfigMap" -}}
{{- end -}}
{{- if or .Values.logging.config .Values.logging.existingConfigMap -}}
  {{- range .Values.or.env -}}
    {{- if eq .name "OR_LOGGING_CONFIG_FILE" -}}
{{- fail "manager chart values logging.* manage OR_LOGGING_CONFIG_FILE; remove OR_LOGGING_CONFIG_FILE from or.env" -}}
    {{- end -}}
  {{- end -}}
{{- end -}}
{{- end }}

{{/*
Validate OpenTelemetry values.
*/}}
{{- define "manager.otel.validate" -}}
{{- if .Values.or.otel.enabled -}}
  {{- if not .Values.or.otel.serviceName -}}
{{- fail "manager chart value or.otel.serviceName is required when or.otel.enabled is true" -}}
  {{- end -}}
  {{- if not .Values.or.otel.endpoint -}}
{{- fail "manager chart value or.otel.endpoint is required when or.otel.enabled is true" -}}
  {{- end -}}
  {{- if not .Values.or.otel.protocol -}}
{{- fail "manager chart value or.otel.protocol is required when or.otel.enabled is true" -}}
  {{- end -}}
  {{- $managedEnvironmentVariables := list
    "OTEL_JAVAAGENT_ENABLED"
    "OTEL_SERVICE_NAME"
    "OTEL_TRACES_EXPORTER"
    "OTEL_EXPORTER_OTLP_ENDPOINT"
    "OTEL_EXPORTER_OTLP_PROTOCOL"
    "OTEL_METRICS_EXPORTER"
    "OTEL_LOGS_EXPORTER"
    "OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED"
  -}}
  {{- range .Values.or.env -}}
    {{- if has .name $managedEnvironmentVariables -}}
{{- fail (printf "manager chart values or.otel.* manage %s; remove %s from or.env" .name .name) -}}
    {{- end -}}
  {{- end -}}
{{- end -}}
{{- end }}

{{/*
Return the chart-managed logging ConfigMap name.
*/}}
{{- define "manager.logging.configMapName" -}}
{{- printf "%s-%s" (include "manager.fullname" .) "logging" | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{/*
Return the referenced logging ConfigMap name.
*/}}
{{- define "manager.logging.configMapRefName" -}}
{{- if .Values.logging.existingConfigMap -}}
{{- .Values.logging.existingConfigMap -}}
{{- else -}}
{{- include "manager.logging.configMapName" . -}}
{{- end -}}
{{- end }}

{{/*
Return the referenced logging ConfigMap key.
*/}}
{{- define "manager.logging.configMapKey" -}}
{{- if .Values.logging.existingConfigMap -}}
{{- default "logging.properties" .Values.logging.existingConfigMapKey -}}
{{- else -}}
logging.properties
{{- end -}}
{{- end }}

{{/*
Return the mounted logging config path.
*/}}
{{- define "manager.logging.configFilePath" -}}
/etc/openremote/logging/logging.properties
{{- end }}
