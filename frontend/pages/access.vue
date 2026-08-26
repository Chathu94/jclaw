<script setup lang="ts">
import { CheckIcon, KeyIcon, PlusIcon, ShieldCheckIcon, UserGroupIcon } from '@heroicons/vue/24/outline'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '~/components/ui/dialog'
import type { Team, Tenant, UserAccount } from '~/types/api'
import { MIN_PASSWORD_LENGTH, estimatePasswordStrength } from '~/utils/passwordStrength'

const [{ data: tenants, refresh: refreshTenants }, { data: teams, refresh: refreshTeams }, { data: users, refresh: refreshUsers }] = await Promise.all([
  useFetch<Tenant[]>('/api/access/tenants', { default: () => [] }),
  useFetch<Team[]>('/api/access/teams', { default: () => [] }),
  useFetch<UserAccount[]>('/api/access/users', { default: () => [] }),
])

const tenantForm = reactive({ slug: '', name: '' })
const teamForm = reactive({ tenantId: '', slug: '', name: '' })
const userForm = reactive({ username: '', displayName: '', password: '', tenantId: '', teamId: '', role: 'USER' as UserAccount['role'] })
const passwordDialog = reactive({
  open: false,
  user: null as UserAccount | null,
  password: '',
  confirmPassword: '',
  error: null as string | null,
})
const resetPasswordId = useId()
const resetConfirmPasswordId = useId()
const saving = ref(false)
const error = ref<string | null>(null)

const pendingAdmins = computed(() => (users.value ?? []).filter(u => u.role !== 'USER' && !u.approved))
const teamsForUserTenant = computed(() =>
  (teams.value ?? []).filter(t => String(t.tenantId) === userForm.tenantId),
)
const passwordDialogTitle = computed(() => {
  if (!passwordDialog.user) return 'Set password'
  return `${passwordDialog.user.passwordSet ? 'Reset' : 'Set'} password for ${passwordDialog.user.username}`
})
const passwordLengthOk = computed(() => passwordDialog.password.length >= MIN_PASSWORD_LENGTH)
const passwordsMatch = computed(() =>
  !!passwordDialog.password && passwordDialog.password === passwordDialog.confirmPassword,
)
const passwordStrength = computed(() => estimatePasswordStrength(passwordDialog.password))
const canSavePassword = computed(() =>
  !saving.value && passwordLengthOk.value && passwordsMatch.value,
)
const passwordStrengthClass = computed(() => {
  const score = passwordStrength.value.score
  if (score <= 1) return 'text-red-600 dark:text-red-400'
  if (score === 2) return 'text-amber-600 dark:text-amber-400'
  return 'text-emerald-700 dark:text-emerald-400'
})

watch(() => tenants.value, (rows) => {
  if (!teamForm.tenantId && rows?.[0]) teamForm.tenantId = String(rows[0].id)
  if (!userForm.tenantId && rows?.[0]) userForm.tenantId = String(rows[0].id)
}, { immediate: true })

watch(teamsForUserTenant, (rows) => {
  if (!rows.some(t => String(t.id) === userForm.teamId)) {
    userForm.teamId = rows[0] ? String(rows[0].id) : ''
  }
}, { immediate: true })

async function refreshAll() {
  await Promise.all([refreshTenants(), refreshTeams(), refreshUsers()])
}

async function createTenant() {
  await submit(async () => {
    await $fetch('/api/access/tenants', { method: 'POST', body: clean(tenantForm) })
    tenantForm.slug = ''
    tenantForm.name = ''
  })
}

async function createTeam() {
  await submit(async () => {
    await $fetch('/api/access/teams', {
      method: 'POST',
      body: { tenantId: Number(teamForm.tenantId), slug: teamForm.slug, name: teamForm.name || teamForm.slug },
    })
    teamForm.slug = ''
    teamForm.name = ''
  })
}

async function createUser() {
  await submit(async () => {
    await $fetch('/api/access/users', {
      method: 'POST',
      body: {
        username: userForm.username,
        displayName: userForm.displayName || userForm.username,
        tenantId: Number(userForm.tenantId),
        teamId: Number(userForm.teamId),
        role: userForm.role,
        password: userForm.password,
      },
    })
    userForm.username = ''
    userForm.displayName = ''
    userForm.password = ''
    userForm.role = 'USER'
  })
}

async function approveUser(user: UserAccount) {
  await submit(async () => {
    await $fetch(`/api/access/users/${user.id}/approve`, { method: 'POST' })
  })
}

function openPasswordDialog(user: UserAccount) {
  passwordDialog.user = user
  passwordDialog.password = ''
  passwordDialog.confirmPassword = ''
  passwordDialog.error = null
  passwordDialog.open = true
}

function closePasswordDialog() {
  if (saving.value) return
  passwordDialog.open = false
  passwordDialog.user = null
  passwordDialog.password = ''
  passwordDialog.confirmPassword = ''
  passwordDialog.error = null
}

async function setUserPassword() {
  const user = passwordDialog.user
  if (!user) return
  if (!passwordDialog.password) {
    passwordDialog.error = 'Password is required.'
    return
  }
  if (!passwordsMatch.value) {
    passwordDialog.error = 'Passwords do not match.'
    return
  }
  const password = passwordDialog.password
  saving.value = true
  error.value = null
  passwordDialog.error = null
  try {
    await $fetch(`/api/access/users/${user.id}/password`, {
      method: 'POST',
      body: { password },
    })
    saving.value = false
    closePasswordDialog()
    try {
      await refreshAll()
    }
    catch (e: unknown) {
      error.value = requestErrorMessage(e, 'Password was saved, but refreshing access data failed.')
    }
  }
  catch (e: unknown) {
    passwordDialog.error = passwordErrorMessage(e)
  }
  finally {
    saving.value = false
  }
}

async function submit(fn: () => Promise<void>) {
  saving.value = true
  error.value = null
  try {
    await fn()
    await refreshAll()
  }
  catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Request failed'
  }
  finally {
    saving.value = false
  }
}

function clean(form: { slug: string, name: string }) {
  return { slug: form.slug, name: form.name || form.slug }
}

function roleLabel(role: UserAccount['role']) {
  return role.replace('_', ' ').toLowerCase()
}

function passwordErrorMessage(err: unknown): string {
  const e = err as { data?: { code?: string, message?: string } | string, response?: { _data?: unknown }, message?: string } | undefined
  const data = e?.data ?? e?.response?._data
  const code = typeof data === 'object' && data !== null && 'code' in data
    ? String((data as { code?: unknown }).code)
    : null
  if (code === 'password_too_short') return `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`
  if (code === 'password_too_long') return 'That password is too long.'
  if (code === 'password_breached') return 'This password appears in a known data breach. Choose a different one.'
  if (typeof data === 'object' && data !== null && 'message' in data) {
    const message = (data as { message?: unknown }).message
    if (typeof message === 'string' && message) return message
  }
  if (typeof data === 'string' && data) return data
  return e?.message ?? 'Password reset failed.'
}

function requestErrorMessage(err: unknown, fallback: string): string {
  const e = err as { data?: unknown, response?: { _data?: unknown }, message?: string } | undefined
  const data = e?.data ?? e?.response?._data
  if (typeof data === 'string' && data) return data
  if (typeof data === 'object' && data !== null && 'message' in data) {
    const message = (data as { message?: unknown }).message
    if (typeof message === 'string' && message) return message
  }
  return e?.message ?? fallback
}
</script>

<template>
  <div class="space-y-6">
    <header class="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
      <div>
        <h1 class="text-lg font-semibold text-fg-strong">
          Access
        </h1>
        <p class="mt-1 text-sm text-fg-muted">
          Tenant, team, and user hierarchy.
        </p>
      </div>
      <div class="grid grid-cols-3 gap-2 text-sm">
        <div class="rounded-lg border border-border bg-surface-elevated px-3 py-2">
          <div class="text-lg font-semibold text-fg-strong">
            {{ tenants?.length ?? 0 }}
          </div>
          <div class="text-xs text-fg-muted">
            Tenants
          </div>
        </div>
        <div class="rounded-lg border border-border bg-surface-elevated px-3 py-2">
          <div class="text-lg font-semibold text-fg-strong">
            {{ teams?.length ?? 0 }}
          </div>
          <div class="text-xs text-fg-muted">
            Teams
          </div>
        </div>
        <div class="rounded-lg border border-border bg-surface-elevated px-3 py-2">
          <div class="text-lg font-semibold text-fg-strong">
            {{ users?.length ?? 0 }}
          </div>
          <div class="text-xs text-fg-muted">
            Users
          </div>
        </div>
      </div>
    </header>

    <p
      v-if="error"
      class="rounded-lg border border-red-300/60 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300"
    >
      {{ error }}
    </p>

    <section
      v-if="pendingAdmins.length"
      class="rounded-lg border border-amber-300/60 bg-amber-50 px-4 py-3 dark:border-amber-900/60 dark:bg-amber-950/25"
    >
      <div class="mb-3 flex items-center gap-2 text-sm font-medium text-amber-800 dark:text-amber-200">
        <ShieldCheckIcon class="size-4" />
        Pending admin approval
      </div>
      <div class="divide-y divide-amber-200/70 dark:divide-amber-900/70">
        <div
          v-for="u in pendingAdmins"
          :key="u.id"
          class="flex items-center justify-between gap-3 py-2"
        >
          <div class="min-w-0">
            <div class="truncate text-sm font-medium text-fg-strong">
              {{ u.displayName || u.username }}
            </div>
            <div class="text-xs text-fg-muted">
              {{ roleLabel(u.role) }} · {{ u.tenantSlug }} / {{ u.teamSlug }}
            </div>
          </div>
          <button
            type="button"
            class="inline-flex size-8 items-center justify-center rounded-md border border-amber-400/70 bg-white text-amber-800 hover:bg-amber-100 disabled:opacity-50 dark:bg-amber-950/40 dark:text-amber-200"
            :disabled="saving"
            title="Approve admin"
            @click="approveUser(u)"
          >
            <CheckIcon class="size-4" />
          </button>
        </div>
      </div>
    </section>

    <div class="grid gap-4 xl:grid-cols-3">
      <section class="rounded-lg border border-border bg-surface-elevated p-4">
        <h2 class="mb-3 flex items-center gap-2 text-sm font-semibold text-fg-strong">
          <PlusIcon class="size-4" /> Tenant
        </h2>
        <form
          class="space-y-3"
          @submit.prevent="createTenant"
        >
          <input
            v-model="tenantForm.slug"
            required
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
            placeholder="slug"
            pattern="[a-z0-9][a-z0-9_-]{0,79}"
          >
          <input
            v-model="tenantForm.name"
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
            placeholder="name"
          >
          <button
            class="inline-flex items-center gap-2 rounded-md bg-emerald-600 px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
            :disabled="saving"
          >
            <PlusIcon class="size-4" /> Add tenant
          </button>
        </form>
      </section>

      <section class="rounded-lg border border-border bg-surface-elevated p-4">
        <h2 class="mb-3 flex items-center gap-2 text-sm font-semibold text-fg-strong">
          <UserGroupIcon class="size-4" /> Team
        </h2>
        <form
          class="space-y-3"
          @submit.prevent="createTeam"
        >
          <select
            v-model="teamForm.tenantId"
            required
            aria-label="Tenant for new team"
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
          >
            <option
              v-for="t in tenants"
              :key="t.id"
              :value="String(t.id)"
            >
              {{ t.name }}
            </option>
          </select>
          <input
            v-model="teamForm.slug"
            required
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
            placeholder="slug"
            pattern="[a-z0-9][a-z0-9_-]{0,79}"
          >
          <input
            v-model="teamForm.name"
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
            placeholder="name"
          >
          <button
            class="inline-flex items-center gap-2 rounded-md bg-emerald-600 px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
            :disabled="saving || !teamForm.tenantId"
          >
            <PlusIcon class="size-4" /> Add team
          </button>
        </form>
      </section>

      <section class="rounded-lg border border-border bg-surface-elevated p-4">
        <h2 class="mb-3 flex items-center gap-2 text-sm font-semibold text-fg-strong">
          <ShieldCheckIcon class="size-4" /> User
        </h2>
        <form
          class="space-y-3"
          @submit.prevent="createUser"
        >
          <input
            v-model="userForm.username"
            required
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
            placeholder="username"
          >
          <input
            v-model="userForm.displayName"
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
            placeholder="display name"
          >
          <input
            v-model="userForm.password"
            type="password"
            autocomplete="new-password"
            aria-label="Initial password for new user"
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
            placeholder="initial password"
          >
          <select
            v-model="userForm.tenantId"
            required
            aria-label="Tenant for new user"
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
          >
            <option
              v-for="t in tenants"
              :key="t.id"
              :value="String(t.id)"
            >
              {{ t.name }}
            </option>
          </select>
          <select
            v-model="userForm.teamId"
            required
            aria-label="Team for new user"
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
          >
            <option
              v-for="t in teamsForUserTenant"
              :key="t.id"
              :value="String(t.id)"
            >
              {{ t.name }}
            </option>
          </select>
          <select
            v-model="userForm.role"
            aria-label="Role for new user"
            class="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
          >
            <option value="USER">
              User
            </option>
            <option value="TEAM_ADMIN">
              Team admin
            </option>
            <option value="TENANT_ADMIN">
              Tenant admin
            </option>
            <option value="ALL_ADMIN">
              All admin
            </option>
          </select>
          <button
            class="inline-flex items-center gap-2 rounded-md bg-emerald-600 px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
            :disabled="saving || !userForm.teamId"
          >
            <PlusIcon class="size-4" /> Add user
          </button>
        </form>
      </section>
    </div>

    <section class="rounded-lg border border-border bg-surface-elevated">
      <div class="border-b border-border px-4 py-3 text-sm font-semibold text-fg-strong">
        Users
      </div>
      <div class="overflow-x-auto">
        <table class="min-w-full text-sm">
          <thead class="bg-muted/30 text-xs uppercase text-fg-muted">
            <tr>
              <th class="px-4 py-2 text-left">
                User
              </th>
              <th class="px-4 py-2 text-left">
                Role
              </th>
              <th class="px-4 py-2 text-left">
                Scope
              </th>
              <th class="px-4 py-2 text-left">
                Status
              </th>
              <th class="px-4 py-2 text-left">
                Password
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-border">
            <tr
              v-for="u in users"
              :key="u.id"
            >
              <td class="px-4 py-3">
                <div class="font-medium text-fg-strong">
                  {{ u.displayName || u.username }}
                </div>
                <div class="text-xs text-fg-muted">
                  {{ u.username }}
                </div>
              </td>
              <td class="px-4 py-3 capitalize">
                {{ roleLabel(u.role) }}
              </td>
              <td class="px-4 py-3 text-fg-muted">
                {{ u.tenantSlug || 'all' }} / {{ u.teamSlug || 'all' }}
              </td>
              <td class="px-4 py-3">
                <span :class="u.approved ? 'text-emerald-600' : 'text-amber-600'">
                  {{ u.approved ? 'approved' : 'pending' }}
                </span>
              </td>
              <td class="px-4 py-3">
                <button
                  type="button"
                  class="inline-flex h-8 items-center gap-2 rounded-md border border-border px-2.5 text-xs font-medium text-fg-strong hover:bg-muted/40 disabled:opacity-50"
                  :disabled="saving"
                  :aria-label="`${u.passwordSet ? 'Reset' : 'Set'} password for ${u.username}`"
                  @click="openPasswordDialog(u)"
                >
                  <KeyIcon class="size-4" />
                  {{ u.passwordSet ? 'Reset' : 'Set' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <Dialog
      :open="passwordDialog.open"
      @update:open="value => value ? (passwordDialog.open = true) : closePasswordDialog()"
    >
      <DialogContent class="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{{ passwordDialogTitle }}</DialogTitle>
          <DialogDescription>
            Enter the new password twice before saving it for this user.
          </DialogDescription>
        </DialogHeader>
        <form
          class="space-y-5"
          @submit.prevent="setUserPassword"
        >
          <div class="grid gap-4 sm:grid-cols-2">
            <label
              :for="resetPasswordId"
              class="block"
            >
              <span class="mb-1.5 block text-sm font-medium text-fg-strong">New password</span>
              <input
                :id="resetPasswordId"
                v-model="passwordDialog.password"
                type="password"
                autocomplete="new-password"
                class="h-10 w-full rounded-md border border-border bg-surface px-3 text-sm text-fg-strong outline-hidden focus:ring-2 focus:ring-emerald-500/35"
                required
                :minlength="MIN_PASSWORD_LENGTH"
              >
            </label>
            <label
              :for="resetConfirmPasswordId"
              class="block"
            >
              <span class="mb-1.5 block text-sm font-medium text-fg-strong">Confirm password</span>
              <input
                :id="resetConfirmPasswordId"
                v-model="passwordDialog.confirmPassword"
                type="password"
                autocomplete="new-password"
                class="h-10 w-full rounded-md border border-border bg-surface px-3 text-sm text-fg-strong outline-hidden focus:ring-2 focus:ring-emerald-500/35"
                required
                :minlength="MIN_PASSWORD_LENGTH"
              >
            </label>
          </div>
          <div class="rounded-md border border-border bg-muted/20 px-3 py-2 text-xs">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <span class="text-fg-muted">Strength</span>
              <span :class="passwordStrengthClass">{{ passwordStrength.label || 'Enter a password' }}</span>
            </div>
            <div class="mt-2 grid gap-1 sm:grid-cols-2">
              <span :class="passwordLengthOk ? 'text-emerald-700 dark:text-emerald-400' : 'text-fg-muted'">
                At least {{ MIN_PASSWORD_LENGTH }} characters
              </span>
              <span :class="passwordsMatch ? 'text-emerald-700 dark:text-emerald-400' : 'text-fg-muted'">
                Passwords match
              </span>
            </div>
          </div>
          <p
            v-if="passwordDialog.error"
            class="rounded-md border border-red-300/60 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300"
          >
            {{ passwordDialog.error }}
          </p>
          <DialogFooter class="gap-2">
            <button
              type="button"
              class="inline-flex h-9 items-center justify-center rounded-md border border-border px-3 text-sm font-medium text-fg-strong hover:bg-muted/40 disabled:opacity-50"
              :disabled="saving"
              @click="closePasswordDialog"
            >
              Cancel
            </button>
            <button
              type="submit"
              class="inline-flex h-9 items-center justify-center rounded-md bg-emerald-600 px-3 text-sm font-medium text-white hover:bg-emerald-700 disabled:opacity-50"
              :disabled="!canSavePassword"
            >
              {{ saving ? 'Saving...' : 'Save password' }}
            </button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  </div>
</template>
