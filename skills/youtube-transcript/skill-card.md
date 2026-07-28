## Description: <br>
Fetch and summarize YouTube video transcripts from a video ID or URL, using a residential IP route when needed to avoid cloud IP transcript blocks. <br>

This skill is ready for commercial/non-commercial use. <br>

## Publisher: <br>
[xthezealot](https://clawhub.ai/user/xthezealot) <br>

### License/Terms of Use: <br>


## Use Case: <br>
Developers and agents use this skill to retrieve transcript JSON from a YouTube video ID or URL so they can summarize, transcribe, or extract video content. It is intended for environments where a configured residential WireGuard route is acceptable. <br>

### Deployment Geography for Use: <br>
Global <br>

## Known Risks and Mitigations: <br>
Risk: The skill can automatically change the host's WireGuard VPN and routing state during normal use. <br>
Mitigation: Install only when this routing behavior is intended, and prefer an isolated environment with a dedicated, tightly scoped WireGuard configuration. <br>
Risk: Video IDs or URLs may be sent to YouTube-related services and noembed.com while fetching transcript metadata and content. <br>
Mitigation: Use the skill only with video identifiers that are acceptable to share with those services. <br>


## Reference(s): <br>
- [YouTube Transcript Setup Guide](references/SETUP.md) <br>


## Skill Output: <br>
**Output Type(s):** [JSON, Text, Guidance] <br>
**Output Format:** [JSON object with video metadata, full_text, and timestamped transcript entries] <br>
**Output Parameters:** [1D] <br>
**Other Properties Related to Output:** [Requires a YouTube video ID or URL and optional comma-separated language codes; may change local WireGuard and routing state before fetching.] <br>

## Skill Version(s): <br>
1.0.1 (source: server release metadata) <br>

## Ethical Considerations: <br>
Users should evaluate whether this skill is appropriate for their environment, review any generated or modified files before relying on them, and apply their organization's safety, security, and compliance requirements before deployment. <br>
