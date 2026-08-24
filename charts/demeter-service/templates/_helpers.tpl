{{- define "demeter.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "demeter.fullname" -}}
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

{{- define "demeter.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "demeter.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "demeter.selectorLabels" -}}
app.kubernetes.io/name: {{ include "demeter.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "demeter.postgresFullname" -}}
{{- printf "%s-postgresql" (include "demeter.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
The JDBC URL the service connects with. When the chart runs its own Postgres the
URL is derived from the StatefulSet's headless service, so nobody has to keep a
hand-written URL in step with the release name.
*/}}
{{- define "demeter.jdbcUrl" -}}
{{- if .Values.postgresql.enabled -}}
jdbc:postgresql://{{ include "demeter.postgresFullname" . }}:5432/{{ .Values.postgresql.database }}
{{- else -}}
{{ required "externalDatabase.jdbcUrl is required when postgresql.enabled is false" .Values.externalDatabase.jdbcUrl }}
{{- end -}}
{{- end }}

{{- define "demeter.dbUser" -}}
{{- if .Values.postgresql.enabled -}}{{ .Values.postgresql.username }}{{- else -}}{{ required "externalDatabase.username is required when postgresql.enabled is false" .Values.externalDatabase.username }}{{- end -}}
{{- end }}
