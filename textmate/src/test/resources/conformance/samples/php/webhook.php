<?php
declare(strict_types=1);

namespace App\Webhooks;

/**
 * Handles a push event.
 */
final class PushHandler
{
    private const MAX_COMMITS = 20;

    public function __construct(private readonly string $secret) {}

    public function handle(array $payload): string
    {
        $branch = $payload['ref'] ?? 'refs/heads/main';
        $count = count($payload['commits'] ?? []);
        // Short summary
        return sprintf("%s: %d commit%s", basename($branch), min($count, self::MAX_COMMITS), $count === 1 ? '' : 's');
    }
}
?>
<p>Last push: <?= htmlspecialchars($summary ?? "none") ?></p>
