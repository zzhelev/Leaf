<script lang="ts">
  export let stashes: { index: number; message: string }[] = [];
  let selected = 0;
  $: count = stashes.length;
</script>

<h2>{count} stash{count === 1 ? "" : "es"}</h2>

{#if count > 0}
  <ul>
    {#each stashes as stash (stash.index)}
      <li class:selected={stash.index === selected} on:click={() => (selected = stash.index)}>
        stash@&#123;{stash.index}&#125;: {stash.message}
      </li>
    {/each}
  </ul>
{:else}
  <p>No stashes</p>
{/if}

<style>
  .selected { background: #eef4fb; }
</style>
