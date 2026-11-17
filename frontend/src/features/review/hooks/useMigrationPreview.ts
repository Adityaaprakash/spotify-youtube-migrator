import { useMutation } from '@tanstack/react-query';
import { reviewApi } from '../api';
import { MigrationPlanPreviewRequest, MigrationPlanResponse } from '../types';

export function useMigrationPreview() {
  return useMutation<MigrationPlanResponse, Error, MigrationPlanPreviewRequest>({
    mutationFn: (request) => reviewApi.generatePreview(request),
  });
}
