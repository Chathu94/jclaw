/**
 * Frontend half of the tool-icon contract whose authoritative half is
 * {@code ToolRegistry.Tool#icon()} on the JVM.
 *
 * Every key the backend can emit must have an entry here. `ToolIconContractTest`
 * enumerates the live registry and fails the build when one is missing — three
 * hand-synced copies of this dictionary previously drifted, and the agent detail
 * page rendered an empty box for `brain` and `printer`.
 */

import {
  ArrowUturnLeftIcon,
  BookOpenIcon,
  ChatBubbleLeftRightIcon,
  CheckCircleIcon,
  ClipboardDocumentCheckIcon,
  ClockIcon,
  Cog6ToothIcon,
  CommandLineIcon,
  ComputerDesktopIcon,
  CpuChipIcon,
  DocumentTextIcon,
  FolderIcon,
  GlobeAltIcon,
  MagnifyingGlassIcon,
  MicrophoneIcon,
  PaperAirplaneIcon,
  PauseIcon,
  PhotoIcon,
  PrinterIcon,
  PuzzlePieceIcon,
  QueueListIcon,
  SpeakerWaveIcon,
  UsersIcon,
  VideoCameraIcon,
  WrenchIcon,
} from '@heroicons/vue/24/outline'
import type { FunctionalComponent } from 'vue'

const TOOL_ICONS: Record<string, FunctionalComponent> = {
  'book': BookOpenIcon,
  'brain': CpuChipIcon,
  'browser': ComputerDesktopIcon,
  'chat-bubble': ChatBubbleLeftRightIcon,
  'check': CheckCircleIcon,
  'clock': ClockIcon,
  'cog': Cog6ToothIcon,
  'document': DocumentTextIcon,
  'folder': FolderIcon,
  'globe': GlobeAltIcon,
  'history': ArrowUturnLeftIcon,
  'image': PhotoIcon,
  'list': QueueListIcon,
  'mic': MicrophoneIcon,
  'pause': PauseIcon,
  'plug': PuzzlePieceIcon,
  'printer': PrinterIcon,
  'search': MagnifyingGlassIcon,
  'send': PaperAirplaneIcon,
  'shell': CommandLineIcon,
  'speaker': SpeakerWaveIcon,
  'tasks': ClipboardDocumentCheckIcon,
  'terminal': CommandLineIcon,
  'users': UsersIcon,
  'video': VideoCameraIcon,
  'wrench': WrenchIcon,
}

// Heroicons' PaperAirplaneIcon points up-and-right at ~45°; the chat composer's
// send button applies -rotate-45 to make it horizontal, the conventional "send"
// affordance. Mirror it so the `send` tool icon reads the same way everywhere.
const TOOL_ICON_EXTRA_CLASS: Record<string, string> = {
  send: '-rotate-45',
}

/**
 * Resolve a backend icon key to its Heroicons component. Unknown and absent
 * keys both fall back to the wrench — the same glyph `wrench` itself maps to,
 * since that is the registry's own name for "generic tool". Never returns null:
 * a caller must not have to guard, or the icon silently disappears.
 */
export function toolIconFor(key: string | null | undefined): FunctionalComponent {
  return (key && TOOL_ICONS[key]) || WrenchIcon
}

/** Per-icon-key Tailwind overrides; empty string when the key needs none. */
export function toolIconClassFor(key: string | null | undefined): string {
  return (key && TOOL_ICON_EXTRA_CLASS[key]) || ''
}
