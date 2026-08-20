/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.nats.subject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import static org.sensorhub.impl.service.consys.nats.subject.ConSysSubjectValidator.ResourceType.*;


/**
 * <p>
 * Validates NATS subject patterns against the OGC Connected Systems API Part 3
 * (Pub/Sub) channel hierarchy, with support for NATS wildcards ({@code *} and
 * {@code >}).
 * </p>
 *
 * <p>
 * This is a direct port of the MQTT binding's {@code ConSysTopicValidator}
 * (duplicated per project decision), adapted to NATS subject syntax. The
 * <em>structure</em> of the hierarchy is identical to the MQTT topic hierarchy;
 * only the syntax differs, as required by NATS:
 * </p>
 * <ul>
 *   <li>token separator is {@code .} (NATS) instead of {@code /} (MQTT)</li>
 *   <li>single-token wildcard is {@code *} (NATS) instead of {@code +} (MQTT)</li>
 *   <li>multi-token wildcard is {@code >} (NATS) instead of {@code #} (MQTT)</li>
 * </ul>
 *
 * <p>
 * As in MQTT, wildcards may only appear in <em>resource ID</em> positions —
 * never in resource type segments (e.g. {@code systems}, {@code datastreams}).
 * For example, {@code systems.*.datastreams.*} is valid; {@code *.134} is not.
 * </p>
 *
 * <p>
 * Usage: strip the nodeId/endpoint prefix from a subject first, then call
 * {@link #matchEventSubject(String)} to validate structure and identify the
 * resource type (for permission mapping).
 * </p>
 *
 * @author Ian Patterson (MQTT original), CR31 (NATS port)
 * @since June 29, 2026
 */
public final class ConSysSubjectValidator
{
    private ConSysSubjectValidator() {}


    /**
     * CS API resource types, used for permission mapping after subject matching.
     */
    public enum ResourceType
    {
        SYSTEM, DATASTREAM, CONTROLSTREAM, OBSERVATION, COMMAND, DEPLOYMENT, PROCEDURE, PROPERTY
    }


    /**
     * A structural pattern for a CS API subject. Each element in {@code segments}
     * is either a literal string (resource type keyword) or {@code null} (marks a
     * resource ID position where {@code *} or {@code >} wildcards are valid).
     */
    private record SubjectPattern(ResourceType type, String[] segments)
    {
        // Templates are authored with '/' separators for readability; the parser
        // splits them into segments where "{id}" becomes null.
        static SubjectPattern of(ResourceType type, String template)
        {
            var templateParts = template.split("/", -1);
            var segments = new String[templateParts.length];
            for (int i = 0; i < templateParts.length; i++)
                segments[i] = "{id}".equals(templateParts[i]) ? null : templateParts[i];
            return new SubjectPattern(type, segments);
        }
    }


    /**
     * All known CS API Part 3 resource event subject patterns (after stripping
     * the nodeId prefix). Ordered least-specific first so that when {@code >}
     * appears at an ID position the shallowest — and therefore most semantically
     * correct — ResourceType is returned first. This list is IDENTICAL to the
     * MQTT binding's pattern set.
     */
    private static final List<SubjectPattern> EVENT_PATTERNS = List.of(
        // 2-segment patterns (least specific)
        SubjectPattern.of(SYSTEM,        "systems/{id}"),
        SubjectPattern.of(DEPLOYMENT,    "deployments/{id}"),
        SubjectPattern.of(PROCEDURE,     "procedures/{id}"),
        SubjectPattern.of(PROPERTY,      "properties/{id}"),

        // 4-segment patterns
        SubjectPattern.of(SYSTEM,        "systems/{id}/subsystems/{id}"),
        SubjectPattern.of(SYSTEM,        "systems/{id}/deployments/{id}"),
        SubjectPattern.of(DATASTREAM,    "systems/{id}/datastreams/{id}"),
        SubjectPattern.of(CONTROLSTREAM, "systems/{id}/controlstreams/{id}"),
        SubjectPattern.of(DEPLOYMENT,    "deployments/{id}/subdeployments/{id}"),

        // 6-segment patterns
        SubjectPattern.of(OBSERVATION,   "systems/{id}/datastreams/{id}/observations/{id}"),
        SubjectPattern.of(COMMAND,       "systems/{id}/controlstreams/{id}/commands/{id}"),

        // 8-segment patterns (most specific)
        SubjectPattern.of(COMMAND,       "systems/{id}/controlstreams/{id}/commands/{id}/status/{id}"),
        SubjectPattern.of(COMMAND,       "systems/{id}/controlstreams/{id}/commands/{id}/result/{id}")
    );


    /**
     * Match a subject path (after stripping the nodeId or endpoint prefix)
     * against all known CS API resource event subject patterns.
     *
     * <p>Wildcards are accepted only in {@code {id}} positions. A {@code *}
     * matches exactly one ID segment; a {@code >} matches an ID segment and
     * everything below (must be the last segment).</p>
     *
     * @param path the stripped subject path, dot-separated,
     *             e.g. {@code "systems.*.datastreams.*"}
     * @return the matched {@link ResourceType}, or empty if no pattern matches
     *         (invalid subject or wildcard in a non-ID position)
     */
    public static Optional<ResourceType> matchEventSubject(String path)
    {
        var subjectSegments = path.split("\\.", -1);
        for (var pattern : EVENT_PATTERNS)
        {
            var result = matchPattern(subjectSegments, pattern.segments(), pattern.type());
            if (result.isPresent())
                return result;
        }
        return Optional.empty();
    }


    /**
     * Returns {@code true} if the subject contains any NATS wildcard tokens
     * ({@code *} or {@code >}).
     */
    public static boolean hasWildcard(String subject)
    {
        return subject.contains("*") || subject.contains(">");
    }


    /** Subject suffix marking a Resource Data channel. Identical to the MQTT binding. */
    public static final String DATA_SUFFIX = ":data";


    /**
     * Format subtopic tokens recognised on Resource Data channels. Per OGC CS
     * API Part 3 "Resource Data Messages Content Negotiation", a data channel
     * may carry an explicit wire-format token as a trailing subtopic
     * (e.g. {@code …observations:data.swe-json}). Hyphens substitute for the
     * MIME {@code +} separator so tokens stay legal across MQTT, NATS, and Kafka
     * broker namespaces. Token set is IDENTICAL to the MQTT binding.
     */
    public static final Map<String, ResourceFormat> FORMAT_SUBTOPICS = Map.of(
        "json",       ResourceFormat.JSON,
        "swe-json",   ResourceFormat.SWE_JSON,
        "swe-binary", ResourceFormat.SWE_BINARY,
        "swe-csv",    ResourceFormat.SWE_TEXT,
        // application/swe+proto — registered as a CustomObsFormat by the
        // sensorhub-service-consys-proto module.
        "swe-proto",  ResourceFormat.fromMimeType("application/swe+proto"),
        // application/swe+flatbuffers — registered as a CustomObsFormat by the
        // sensorhub-service-consys-flatbuffers module (self-describing FlexBuffers).
        "swe-flatbuffers", ResourceFormat.fromMimeType("application/swe+flatbuffers"),
        "om-json",    ResourceFormat.OM_JSON,
        "sml-json",   ResourceFormat.SML_JSON
    );


    /**
     * Returns {@code true} if {@code subject} ends with the Resource Data marker —
     * either bare {@code :data} or {@code :data.<token>}.
     */
    public static boolean isDataSubject(String subject)
    {
        if (subject == null)
            return false;
        int idx = subject.lastIndexOf(DATA_SUFFIX);
        if (idx < 0)
            return false;
        int after = idx + DATA_SUFFIX.length();
        return after == subject.length() || subject.charAt(after) == '.';
    }


    /**
     * Parse the format subtopic from a Resource Data subject.
     *
     * @return the {@link ResourceFormat} for {@code :data.<token>}, or
     *         {@link Optional#empty()} for bare {@code :data} (server-default
     *         negotiation), or for a subject with no {@code :data} suffix at all.
     * @throws InvalidSubjectException if a token is present but not in
     *         {@link #FORMAT_SUBTOPICS}.
     */
    public static Optional<ResourceFormat> parseDataSubjectFormat(String subject)
            throws InvalidSubjectException
    {
        if (subject == null)
            return Optional.empty();
        int idx = subject.lastIndexOf(DATA_SUFFIX);
        if (idx < 0)
            return Optional.empty();
        int after = idx + DATA_SUFFIX.length();
        if (after >= subject.length())
            return Optional.empty();              // bare ":data"
        if (subject.charAt(after) != '.')
            return Optional.empty();              // ":data" embedded mid-path
        String token = subject.substring(after + 1);
        if (token.isEmpty())
            return Optional.empty();
        ResourceFormat fmt = FORMAT_SUBTOPICS.get(token);
        if (fmt == null)
            throw new InvalidSubjectException(
                "Unknown :data format subtopic: '" + token + "'. Known: "
                + FORMAT_SUBTOPICS.keySet());
        return Optional.of(fmt);
    }


    /**
     * Strip the trailing {@code :data} or {@code :data.<token>} suffix from a
     * Resource Data subject, leaving the underlying resource path.
     */
    public static String stripDataSuffix(String subject)
    {
        if (subject == null)
            return null;
        int idx = subject.lastIndexOf(DATA_SUFFIX);
        if (idx < 0)
            return subject;
        return subject.substring(0, idx);
    }


    /**
     * Attempt to match {@code subjectSegments} against {@code patternSegments}.
     *
     * <p>Rules (identical semantics to the MQTT binding, NATS wildcard syntax):
     * <ul>
     *   <li>A literal pattern segment must equal the corresponding subject segment.</li>
     *   <li>A {@code null} pattern segment (ID position) accepts any concrete ID or {@code *}.</li>
     *   <li>{@code *} is only valid at a {@code null} (ID) pattern position.</li>
     *   <li>{@code >} must always be the last subject segment (standard NATS). It is valid
     *       at an ID position (replacing that resource ID and everything below) or trailing
     *       after full pattern exhaustion. It is invalid at a keyword position.</li>
     * </ul>
     * </p>
     */
    private static Optional<ResourceType> matchPattern(
        String[] subjectSegments, String[] patternSegments, ResourceType type)
    {
        int subjectIdx = 0, patternIdx = 0;

        while (subjectIdx < subjectSegments.length)
        {
            var subjectSeg = subjectSegments[subjectIdx];

            if (">".equals(subjectSeg))
            {
                // > must be the last subject segment (standard NATS)
                if (subjectIdx != subjectSegments.length - 1)
                    return Optional.empty();

                // Valid at an ID (null) position in the pattern — replaces that resource ID
                if (patternIdx < patternSegments.length && patternSegments[patternIdx] == null)
                    return Optional.of(type);

                // Valid as a trailing wildcard after the pattern is fully consumed
                if (patternIdx == patternSegments.length)
                    return Optional.of(type);

                // Invalid: > where a keyword is expected
                return Optional.empty();
            }

            if (patternIdx >= patternSegments.length)
                return Optional.empty(); // more subject segments than pattern, no > to terminate

            var patternSeg = patternSegments[patternIdx]; // null = ID position

            if ("*".equals(subjectSeg))
            {
                if (patternSeg == null) { subjectIdx++; patternIdx++; }
                else return Optional.empty(); // * in a keyword position
            }
            else
            {
                if (patternSeg == null)                   { subjectIdx++; patternIdx++; } // concrete ID in ID slot
                else if (subjectSeg.equals(patternSeg))   { subjectIdx++; patternIdx++; } // keyword matches
                else return Optional.empty();                                              // keyword mismatch
            }
        }

        // Exact match: both pattern and subject fully consumed
        if (patternIdx == patternSegments.length)
            return Optional.of(type);

        return Optional.empty();
    }
}
