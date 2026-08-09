<script setup lang="ts">
import type { DialogRootEmits, DialogRootProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import { useForwardPropsEmits } from "reka-ui"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { cn } from "~/composables/ui-utils"
import Command from "./Command.vue"

const props = withDefaults(defineProps<DialogRootProps & {
  title?: string
  description?: string
  class?: HTMLAttributes["class"]
  showCloseButton?: boolean
}>(), {
  title: "Command Palette",
  description: "Search for a command to run...",
  showCloseButton: true,
})
const emits = defineEmits<DialogRootEmits>()

const forwarded = useForwardPropsEmits(reactiveOmit(props, "class", "showCloseButton"), emits)
</script>

<template>
  <Dialog v-slot="slotProps" v-bind="forwarded">
    <DialogContent
      :class="cn('overflow-hidden p-0', props.class)"
      :show-close-button="showCloseButton"
    >
      <DialogHeader class="sr-only">
        <DialogTitle>{{ title }}</DialogTitle>
        <DialogDescription>{{ description }}</DialogDescription>
      </DialogHeader>
      <!-- Search row is 36px outside a dialog (CommandInput's own h-9) but must
           be 48px inside one: DialogContent's close X is absolute top-4 with a
           16px glyph, so it centres at 24px and hangs below a 36px row. -->
      <Command class="**:data-[slot=command-input-wrapper]:h-12">
        <slot v-bind="slotProps" />
      </Command>
    </DialogContent>
  </Dialog>
</template>
