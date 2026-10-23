<template>
  <ul class="branches">
    <!-- current branch first -->
    <li v-for="branch in sorted" :key="branch.name" :class="{ current: branch.current }" @click="select(branch)">
      {{ branch.name }} <small v-if="branch.ahead">↑{{ branch.ahead }}</small>
    </li>
  </ul>
</template>

<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{ branches: { name: string; current: boolean; ahead: number }[] }>();
const emit = defineEmits<{ (e: "select", name: string): void }>();
const sorted = computed(() => [...props.branches].sort((a, b) => Number(b.current) - Number(a.current)));
function select(branch: { name: string }) {
  emit("select", branch.name);
}
</script>

<style scoped>
.branches .current {
  font-weight: 600;
  color: v-bind("'#1a69b5'");
}
</style>
