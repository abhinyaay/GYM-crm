import {
  Anchor, Box, Button, Container, Group, NavLink, Paper, ScrollArea, Stack, Text, Title, TypographyStylesProvider,
} from '@mantine/core';
import { IconArrowLeft, IconBarbell, IconBrandGithub } from '@tabler/icons-react';
import { Children, isValidElement, type ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import { Link } from 'react-router-dom';
import remarkGfm from 'remark-gfm';
import guide from '@/content/setup-guide.md?raw';
import { useAuth } from '@/auth/AuthContext';

const REPO = 'https://github.com/abhinyaay/GYM-crm';

/** "3. Install on one computer" -> "3-install-on-one-computer" (stable ids for the table of contents). */
export function slug(text: string): string {
  return text.toLowerCase().replace(/[^\p{L}\p{N}\s-]/gu, '').trim().replace(/\s+/g, '-');
}

function textOf(node: ReactNode): string {
  return Children.toArray(node).map((c) => (typeof c === 'string' || typeof c === 'number' ? String(c)
    : isValidElement<{ children?: ReactNode }>(c) ? textOf(c.props.children) : '')).join('');
}

/** Links to files in the repository open on GitHub; in-page anchors and full URLs are kept. */
export function resolveHref(href: string | undefined): string | undefined {
  if (!href || /^(https?:|mailto:|#)/.test(href)) return href;
  const parts = ['docs'];
  for (const p of href.split('/')) {
    if (p === '..') parts.pop();
    else if (p !== '.' && p !== '') parts.push(p);
  }
  return `${REPO}/blob/main/${parts.join('/')}`;
}

const components: Components = {
  h2: ({ children }) => <h2 id={slug(textOf(children))}>{children}</h2>,
  h3: ({ children }) => <h3 id={slug(textOf(children))}>{children}</h3>,
  a: ({ href, children }) => {
    const url = resolveHref(href);
    const external = url?.startsWith('http');
    return <a href={url} target={external ? '_blank' : undefined} rel={external ? 'noreferrer' : undefined}>{children}</a>;
  },
  table: ({ children }) => <Box style={{ overflowX: 'auto' }}><table>{children}</table></Box>,
};

/**
 * The non-technical setup guide (docs/SETUP_GUIDE.md), readable without signing in so an owner can follow it
 * before the first login.
 */
export default function GuidePage() {
  const { user } = useAuth();
  const toc = guide.split('\n').filter((l) => /^##\s/.test(l)).map((l) => l.replace(/^##\s+/, '').trim());

  return (
    <Container size="lg" py="xl">
      <Group justify="space-between" mb="lg" wrap="wrap">
        <Group gap="xs">
          <IconBarbell size={28} color="var(--mantine-color-teal-6)" />
          <Title order={2}>GymOps setup guide</Title>
        </Group>
        <Group gap="xs">
          <Button component="a" href={REPO} target="_blank" rel="noreferrer" variant="default" size="xs"
            leftSection={<IconBrandGithub size={14} />}>Source code</Button>
          <Button component={Link} to={user ? '/' : '/login'} size="xs" leftSection={<IconArrowLeft size={14} />}>
            {user ? 'Back to the app' : 'Sign in'}
          </Button>
        </Group>
      </Group>
      <Group align="flex-start" gap="lg" wrap="nowrap">
        {toc.length > 0 && (
          <Paper withBorder radius="md" p="xs" w={250} visibleFrom="md" style={{ position: 'sticky', top: 16, flexShrink: 0 }}>
            <Text size="xs" fw={700} c="dimmed" tt="uppercase" px="sm" py={4}>Contents</Text>
            <ScrollArea.Autosize mah="80vh">
              <Stack gap={0}>
                {toc.map((h) => <NavLink key={h} href={`#${slug(h)}`} label={h} />)}
              </Stack>
            </ScrollArea.Autosize>
          </Paper>
        )}
        <Paper withBorder radius="md" p={{ base: 'md', sm: 'xl' }} style={{ flex: 1, minWidth: 0 }}>
          <TypographyStylesProvider>
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>{guide}</ReactMarkdown>
          </TypographyStylesProvider>
        </Paper>
      </Group>
      <Text size="xs" c="dimmed" ta="center" mt="lg">
        Also available on GitHub: <Anchor href={`${REPO}/blob/main/docs/SETUP_GUIDE.md`} target="_blank">docs/SETUP_GUIDE.md</Anchor>
      </Text>
    </Container>
  );
}
