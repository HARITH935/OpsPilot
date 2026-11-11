{{/* Labels that pick this service's pods: used by the Deployment, the Service and the PodDisruptionBudget */}}
{{- define "service.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* Labels on everything this chart creates */}}
{{- define "service.labels" -}}
{{ include "service.selectorLabels" . }}
app.kubernetes.io/version: {{ include "service.tag" . | quote }}
app.kubernetes.io/part-of: opspilot
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end }}

{{/* The image tag: the service's own, else the umbrella's global one; one of them is required */}}
{{- define "service.tag" -}}
{{- $global := (.Values.global).image | default dict -}}
{{- .Values.image.tag | default $global.tag | required "image.tag or global.image.tag is required" -}}
{{- end }}

{{/* registry/name:tag, e.g. ghcr.io/harith935/opspilot-order-service:sha-218a8ef */}}
{{- define "service.image" -}}
{{- $global := (.Values.global).image | default dict -}}
{{- $registry := .Values.image.registry | default $global.registry | required "image.registry or global.image.registry is required" -}}
{{- printf "%s/%s:%s" $registry (required "image.name is required" .Values.image.name) (include "service.tag" .) -}}
{{- end }}
