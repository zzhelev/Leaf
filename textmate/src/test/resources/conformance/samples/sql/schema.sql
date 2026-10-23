-- Commits and their parents.
CREATE TABLE IF NOT EXISTS commits (
    hash        CHAR(40) PRIMARY KEY,
    author      VARCHAR(255) NOT NULL,
    committed   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    message     TEXT
);

/* Newest commits by an author. */
SELECT c.hash, c.message, COUNT(p.parent) AS parents
FROM commits AS c
LEFT JOIN parents p ON p.child = c.hash
WHERE c.author = 'Ana O''Neil' AND c.committed > NOW() - INTERVAL '7 days'
GROUP BY c.hash, c.message
HAVING COUNT(p.parent) >= 1
ORDER BY c.committed DESC
LIMIT 50 OFFSET 0;
